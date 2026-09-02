/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.List;
import java.util.Map;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Instruction;

/**
 * The narrow branches inside {@code Instructions} that a full turn cannot be made to hit — not
 * because they are unreachable in production, but because reaching them from the outside would mean
 * contriving the very failure they exist to handle (a claim deleted from under a running call, a
 * deployment with no durable-state plugin configured). {@code Instructions} is package-visible for
 * exactly this: driving one instruction directly, against the real dependencies {@link Engines}
 * builds, without needing the decision that would ordinarily have produced it.
 */
@DisplayName("Instructions, driven directly at the seams a full turn cannot reach")
class InstructionsEdgeCasesTest {

  @Nested
  @DisplayName("a call whose asking message is gone")
  class TheAskingMessageIsGone {

    private static ActorTestKit testKit;
    private static Engines.Parts parts;
    private static AgentId agentId;

    @BeforeAll
    static void start() {
      testKit = ClusterOfOne.start();
      AgentType type = AgentType.of("orphaned-call");
      parts = Engines.of(testKit.system(), type, Engines.stalled());
      EntityTypeKey<NessyMessage> key = EntityTypeKey.create(NessyMessage.class, type.name());
      ClusterSharding.get(testKit.system())
          .init(
              Entity.of(
                      key,
                      context ->
                          AgentActor.create(
                              new AgentActor.Dependencies(
                                  type, parts.instructions(), Traces.noop()),
                              AgentId.of(context.getEntityId()),
                              context.getShard()))
                  .withStopMessage(new NessyMessage.Stop(Map.of())));
      agentId = AgentId.of("house-orphaned");
    }

    @AfterAll
    static void stop() {
      testKit.shutdownTestKit();
    }

    /**
     * A turn holding a call whose {@code asked} claim was never written — recovery from a state
     * whose asking message somehow never made it to claims, which {@code callOf} treats as "gone"
     * rather than throwing.
     */
    @Test
    @DisplayName("asking approval for it fails the call instead of asking anyone")
    void an_approval_ask_for_a_missing_call_fails_without_asking_anyone() {
      TurnId turnId = TurnId.of("turn-orphaned-approval");
      AgentState state = AgentState.idle().taking(turnId, "obs-claim");
      CallId callId = CallId.of("missing-call");

      parts
          .instructions()
          .perform(agentId, state, new Instruction.AskApprover(callId, "some_tool"), Map.of());

      ToolResult result = decodedResult(parts, agentId, turnId, callId);
      assertThat(result).isInstanceOf(ToolResult.Failure.class);
      assertThat(((ToolResult.Failure) result).message())
          .isEqualTo("the asking message is gone; the call was not made");
    }

    @Test
    @DisplayName("running it fails the call instead of executing anything")
    void a_tool_run_for_a_missing_call_fails_without_running_anything() {
      TurnId turnId = TurnId.of("turn-orphaned-run");
      AgentState state = AgentState.idle().taking(turnId, "obs-claim");
      CallId callId = CallId.of("missing-call-2");

      parts
          .instructions()
          .perform(agentId, state, new Instruction.RunTool(callId, "some_tool"), Map.of());

      ToolResult result = decodedResult(parts, agentId, turnId, callId);
      assertThat(result).isInstanceOf(ToolResult.Failure.class);
      assertThat(((ToolResult.Failure) result).message())
          .isEqualTo("the asking message is gone; it was not run");
    }

    private static ToolResult decodedResult(
        Engines.Parts parts, AgentId agentId, TurnId turnId, CallId callId) {
      byte[] payload =
          parts.claims().get(agentId, turnId, Instructions.resultKey(callId)).orElseThrow();
      return JsonCodec.of(EngineMapper.INSTANCE, ToolResult.class).decode(payload);
    }

    /**
     * {@code redeem} is handed {@code state.observation()} as the claim key for {@code
     * Remember.Input}, and that field is {@code null} until an agent has actually taken a row —
     * exactly the state a fresh, never-worked agent is in. {@code redeem} treats a null key as
     * "nothing to redeem" rather than querying a claim that could never exist.
     */
    @Test
    @DisplayName("remembering the input of a turn that never took a row redeems nothing")
    void remembering_input_with_no_observation_claim_redeems_nothing() {
      AgentId neverWorked = AgentId.of("house-never-worked");

      parts
          .instructions()
          .perform(neverWorked, AgentState.idle(), new Instruction.Remember.Input(), Map.of());

      assertThat(parts.remembered().of(neverWorked))
          .as("nothing was ever claimed under a null key, so nothing was remembered")
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("forgetting with no durable-state plugin configured")
  class NoDurableStatePlugin {

    private ActorSystem<Void> system;

    @AfterEach
    void shutdown() {
      if (system != null) {
        system.terminate();
      }
    }

    /**
     * {@code deleteState} reads the plugin name straight from config; a blank one means nothing was
     * ever durable, so it returns without touching {@code DurableStateStoreRegistry} — which, on an
     * {@link ActorSystem} that never joined a cluster, is not even reachable. This is the only way
     * to prove that early return without standing up a whole second cluster just to configure it
     * away.
     */
    @Test
    @DisplayName("the other participants are still forgotten")
    void forgetting_still_wipes_memory_backlog_and_claims() {
      Config config = ConfigFactory.parseString("pekko.persistence.state.plugin = \"\"");
      system = ActorSystem.create(Behaviors.empty(), "no-durable-state", config);
      AgentType type = AgentType.of("stateless");
      Engines.Parts parts = Engines.of(system, type, Engines.stalled());
      AgentId agentId = AgentId.of("house-stateless");

      parts.backlog().offer(agentId, new HouseEvents.HouseEvent("kitchen", "door opened"));
      parts.remembered().add(agentId, answer());

      parts.instructions().perform(agentId, AgentState.idle(), new Instruction.Forget(), Map.of());

      assertThat(parts.remembered().of(agentId)).as("memory").isEmpty();
      assertThat(backlogRowCount(parts, agentId)).as("backlog rows").isZero();
    }

    private org.jwcarman.nessy.api.message.AnswerMessage answer() {
      return new org.jwcarman.nessy.api.message.AnswerMessage(
          List.of(new org.jwcarman.nessy.api.block.TextBlock("noted")));
    }

    private int backlogRowCount(Engines.Parts parts, AgentId agentId) {
      Integer rows =
          org.springframework.jdbc.core.simple.JdbcClient.create(parts.dataSource())
              .sql("SELECT count(*) FROM nessy_backlog WHERE agent_id = ?")
              .param(agentId.value())
              .query(Integer.class)
              .single();
      return rows == null ? 0 : rows;
    }
  }
}
