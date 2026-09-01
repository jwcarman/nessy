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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.testing.TestDatabase;

/**
 * An observation becoming a turn, and the turn ending.
 *
 * <p>The whole loop minus the model: told something, the agent takes it off the backlog, renders
 * it, and hands a turn the rendered message; the turn remembers it and reports back; the agent goes
 * idle. Everything the design argued about — the observation boundary dying at the agent, one turn
 * at a time, the turn owning its own document — is either true here or nowhere.
 */
@DisplayName("An observation becoming a turn")
class TurnLifecycleTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final EntityTypeKey<NessyMessage> KEY =
      EntityTypeKey.create(NessyMessage.class, WATCHMAN.name());

  private static ActorTestKit testKit;
  private static Memory memory;

  @BeforeAll
  static void startCluster() {
    testKit = ClusterOfOne.start();
    memory = TranscriptMemory.eternal(TestDatabase.fresh(), WATCHMAN);
    StateTypes.of(testKit.system()).register(WATCHMAN, HouseEvent.class);

    Model saysHello =
        new Model() {
          @Override
          public ModelId id() {
            return ModelId.of("scripted");
          }

          @Override
          public org.jwcarman.nessy.spi.model.ModelStream stream(ModelRequest request) {
            // Echoes what it was given, so the test can prove the context reached it.
            String heard = request.context().lines().getLast().text();
            return Scripts.saying(
                new ModelResult.Answered(
                    new AnswerMessage(List.of(new TextBlock("heard: " + heard))),
                    StopReason.END_TURN,
                    new Usage(1, 1)));
          }
        };

    TurnActor.Dependencies turnDeps =
        new TurnActor.Dependencies(
            WATCHMAN,
            memory,
            saysHello,
            "you watch the house",
            1024,
            new ToolBindings(List.of(), EngineMapper.INSTANCE),
            java.util.Set.<org.jwcarman.nessy.spi.model.Capability>of(),
            Narrator.silent(),
            new Claims(TestDatabase.fresh()),
            ReplyTokens.ephemeral(),
            Runnable::run,
            Traces.noop());

    Turns turns =
        (agentId, turnId, input, agent, carried) ->
            TurnActor.create(turnDeps, agentId, turnId, input, agent, java.util.Map.of());

    AgentActor.Dependencies<HouseEvent> deps =
        new AgentActor.Dependencies<>(
            WATCHMAN,
            HouseEvents.CODEC,
            HouseEvents.KEEP_ALL,
            HouseEvents.RENDERER,
            turns,
            Clock.systemUTC(),
            Traces.noop());

    ClusterSharding.get(testKit.system())
        .init(
            Entity.of(
                    KEY,
                    context ->
                        AgentActor.create(
                            deps, AgentId.of(context.getEntityId()), context.getShard()))
                .withStopMessage(new NessyMessage.Stop(Map.of())));
  }

  @AfterAll
  static void stopCluster() {
    testKit.shutdownTestKit();
  }

  private static void observe(String agentId, HouseEvent event) {
    ClusterSharding.get(testKit.system())
        .entityRefFor(KEY, agentId)
        .tell(new NessyMessage.Observe(HouseEvents.CODEC.encode(event), Map.of()));
  }

  private static AgentState<?> inspect(String agentId) {
    TestProbe<AgentState<?>> probe = testKit.createTestProbe();
    ClusterSharding.get(testKit.system())
        .entityRefFor(KEY, agentId)
        .tell(new NessyMessage.Inspect(probe.ref(), Map.of()));
    return probe.receiveMessage();
  }

  @Test
  @DisplayName("the observation is rendered and lands in the transcript")
  void an_observation_becomes_a_remembered_user_message() {
    observe("house-12", new HouseEvent("kitchen", "door opened"));

    await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () -> {
              Context context = memory.recall(AgentId.of("house-12"));
              assertThat(context.lines())
                  .containsExactly(
                      new Context.Line("user", "kitchen: door opened"),
                      new Context.Line("assistant", "heard: kitchen: door opened"));
            });
  }

  @Test
  void the_agent_is_idle_again_once_the_turn_reports_back() {
    observe("house-13", new HouseEvent("hall", "motion"));

    await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () -> {
              AgentState<?> state = inspect("house-13");
              assertThat(state.busy()).isFalse();
              assertThat(state.hasWork()).isFalse();
            });
  }

  @Test
  @DisplayName("every observation gets its own turn, one after another")
  void a_backlog_drains_one_turn_at_a_time() {
    observe("house-14", new HouseEvent("kitchen", "one"));
    observe("house-14", new HouseEvent("kitchen", "two"));
    observe("house-14", new HouseEvent("kitchen", "three"));

    await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () -> {
              assertThat(memory.recall(AgentId.of("house-14")).lines())
                  .containsExactly(
                      new Context.Line("user", "kitchen: one"),
                      new Context.Line("assistant", "heard: kitchen: one"),
                      new Context.Line("user", "kitchen: two"),
                      new Context.Line("assistant", "heard: kitchen: two"),
                      new Context.Line("user", "kitchen: three"),
                      new Context.Line("assistant", "heard: kitchen: three"));
              assertThat(inspect("house-14").busy()).isFalse();
            });
  }
}
