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

import java.util.List;
import java.util.Map;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
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
import org.jwcarman.nessy.engine.agent.Effect;

/**
 * The narrow branches inside {@code EffectWorker} that a full turn cannot be made to hit — not
 * because they are unreachable in production, but because reaching them from the outside would mean
 * contriving the very failure they exist to handle (a claim deleted from under a running call, a
 * deployment with no durable-state plugin configured). {@code EffectWorker} is package-visible for
 * exactly this: driving one effect directly, against the real dependencies {@link Engines} builds,
 * without needing the decision that would ordinarily have produced it.
 */
@DisplayName("EffectWorker, driven directly at the seams a full turn cannot reach")
class EffectWorkerEdgeCasesTest {

  @Nested
  @DisplayName("a call whose asking message is gone")
  class TheAskingMessageIsGone {

    private static ActorTestKit testKit;
    private static Engines.Parts parts;
    private static AgentId agentId;
    private static AgentType type;

    @BeforeAll
    static void start() {
      testKit = ClusterOfOne.start();
      type = AgentType.of("orphaned-call");
      parts = Engines.of(testKit.system(), type, Engines.stalled());
      EntityTypeKey<NessyMessage> key = EntityTypeKey.create(NessyMessage.class, type.name());
      ClusterSharding.get(testKit.system())
          .init(
              Entity.of(
                      key,
                      context ->
                          AgentActor.create(
                              new AgentActor.Dependencies(
                                  type, parts.effectWorker(), Traces.noop()),
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
          .effectWorker()
          .perform(agentId, state, new Effect.AskApprover(callId, "some_tool"), EffectId.next());

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
          .effectWorker()
          .perform(agentId, state, new Effect.RunTool(callId, "some_tool"), EffectId.next());

      ToolResult result = decodedResult(parts, agentId, turnId, callId);
      assertThat(result).isInstanceOf(ToolResult.Failure.class);
      assertThat(((ToolResult.Failure) result).message())
          .isEqualTo("the asking message is gone; it was not run");
    }

    private static ToolResult decodedResult(
        Engines.Parts parts, AgentId agentId, TurnId turnId, CallId callId) {
      byte[] payload =
          parts.claims().get(agentId, turnId, EffectWorker.resultKey(callId)).orElseThrow();
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
      EffectId effectId = claimedEffect(parts, type, neverWorked, null);

      parts
          .effectWorker()
          .perform(neverWorked, AgentState.idle(), new Effect.Remember.Input(), effectId);

      assertThat(parts.remembered().of(neverWorked))
          .as("nothing was ever claimed under a null key, so nothing was remembered")
          .isEmpty();
    }
  }

  /**
   * A genuinely EXECUTING effect row, so {@code EffectStore#complete} — which now raises if it
   * discharges nothing (see {@code EffectStoreTest}) — has something real to discharge. {@code
   * EffectId.next()} alone, as the legacy {@code AgentActor} path still uses, names no row at all.
   */
  private static EffectId claimedEffect(
      Engines.Parts parts, AgentType type, AgentId agentId, TurnId turnId) {
    EffectId id = parts.effects().insert(type, agentId, turnId, 0, new byte[] {0}, null);
    parts
        .effects()
        .claim(type, agentId, java.time.Instant.now().plus(java.time.Duration.ofMinutes(1)));
    return id;
  }

  @Nested
  @DisplayName("forgetting an agent")
  class Forgetting {

    private ActorTestKit testKit;

    @AfterEach
    void shutdown() {
      if (testKit != null) {
        testKit.shutdownTestKit();
      }
    }

    /**
     * {@code forget} no longer reaches into Pekko's durable-state registry to delete a persisted
     * state row -- that required the {@code ActorSystem} reference {@link EffectWorker} gave up
     * when it started answering through {@link Dispatcher} instead of a cluster entity, and the row
     * it used to delete survives until Task 11 removes the journal machinery that wrote it. What
     * this proves is narrower than the old test's name claimed and no less real: the SQL-backed
     * participants -- memory and the backlog -- are still wiped.
     */
    @Test
    @DisplayName("memory and the backlog are wiped")
    void forgetting_wipes_memory_and_backlog() {
      testKit = ClusterOfOne.start();
      AgentType type = AgentType.of("stateless");
      Engines.Parts parts = Engines.of(testKit.system(), type, Engines.stalled());
      AgentId agentId = AgentId.of("house-stateless");

      parts.backlog().offer(agentId, new HouseEvents.HouseEvent("kitchen", "door opened"));
      parts.remembered().add(agentId, answer());
      EffectId effectId = claimedEffect(parts, type, agentId, null);

      parts.effectWorker().perform(agentId, AgentState.idle(), new Effect.Forget(), effectId);

      assertThat(parts.remembered().of(agentId)).as("memory").isEmpty();
      assertThat(backlogRowCount(parts, agentId)).as("backlog rows").isZero();
    }

    /**
     * C3: forgetting used to leave the agent's own {@code nessy_agent} row and every {@code
     * nessy_effect} row it still owed untouched, so a reaper would claim and re-perform those
     * effects -- calling tools and models for an agent whose memory and claims were already gone --
     * forever. This drives {@code forget} against an agent that genuinely HAS a state row (via
     * {@link AgentStore#save}, reached through the package it lives in) and an outstanding PENDING
     * effect nobody has claimed yet, and proves neither survives: the state row is gone, and a
     * subsequent claim finds nothing -- not the outstanding effect that predates the forget, and
     * not even the {@code Forget} effect's own row, which {@code deleteAgent} sweeps up right along
     * with it.
     */
    @Test
    @DisplayName(
        "an outstanding effect and the state row are both gone, and a subsequent claim finds nothing")
    void forgetting_leaves_no_claimable_effect_and_no_state_row() {
      testKit = ClusterOfOne.start();
      AgentType type = AgentType.of("effect-laden");
      Engines.Parts parts = Engines.of(testKit.system(), type, Engines.stalled());
      AgentId agentId = AgentId.of("house-effect-laden");
      java.time.Instant soon = java.time.Instant.now().plus(java.time.Duration.ofMinutes(1));

      parts.store().save(type, agentId, AgentState.idle().taking(TurnId.of("turn-1"), "obs-claim"));
      // An outstanding obligation nobody has claimed -- exactly what a reaper would otherwise find
      // and re-perform against an agent forgetting just erased everything else for.
      parts.effects().insert(type, agentId, TurnId.of("turn-1"), 0, new byte[] {0}, null);
      EffectId forgetEffectId = claimedEffect(parts, type, agentId, null);

      parts.effectWorker().perform(agentId, AgentState.idle(), new Effect.Forget(), forgetEffectId);

      assertThat(parts.effects().claim(type, agentId, soon))
          .as("no effect this agent owed is left to claim")
          .isEmpty();
      assertThat(effectRowCount(parts, agentId)).as("nessy_effect rows").isZero();
      assertThat(agentRowCount(parts, type, agentId)).as("nessy_agent rows").isZero();
    }

    private org.jwcarman.nessy.api.message.AnswerMessage answer() {
      return new org.jwcarman.nessy.api.message.AnswerMessage(
          List.of(new org.jwcarman.nessy.api.block.TextBlock("noted")));
    }

    private int effectRowCount(Engines.Parts parts, AgentId agentId) {
      Integer rows =
          org.springframework.jdbc.core.simple.JdbcClient.create(parts.dataSource())
              .sql("SELECT count(*) FROM nessy_effect WHERE agent_id = ?")
              .param(agentId.value())
              .query(Integer.class)
              .single();
      return rows == null ? 0 : rows;
    }

    private int agentRowCount(Engines.Parts parts, AgentType type, AgentId agentId) {
      Integer rows =
          org.springframework.jdbc.core.simple.JdbcClient.create(parts.dataSource())
              .sql("SELECT count(*) FROM nessy_agent WHERE agent_type = ? AND agent_id = ?")
              .param(type.name())
              .param(agentId.value())
              .query(Integer.class)
              .single();
      return rows == null ? 0 : rows;
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
