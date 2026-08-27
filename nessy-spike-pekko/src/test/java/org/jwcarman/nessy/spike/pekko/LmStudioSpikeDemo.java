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
package org.jwcarman.nessy.spike.pekko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.typesafe.config.ConfigFactory;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * THROWAWAY SPIKE. The whole gist, driven by a REAL model, across a REAL restart.
 *
 * <p>Tagged {@code live} (and needing {@code container} too, since it uses the Postgres config), so
 * the default build never runs it — {@code nessy.excludedGroups} defaults to {@code
 * live,container}. To watch it:
 *
 * <pre>
 *   ./mvnw -pl nessy-spike-pekko test -Dnessy.excludedGroups= -Dtest=LmStudioSpikeDemo
 * </pre>
 *
 * <p>Needs LM Studio serving {@link LmStudioSpikeModel#MODEL_ID} on {@link
 * LmStudioSpikeModel#BASE_URL}, and the {@code pekko_spike} schema in {@code watchman-postgres}.
 *
 * <p>Unlike the automated tests this asserts loosely on purpose: a real model decides for itself
 * which tools to call, and what it actually does is the observation the spike wants.
 */
@Tag("live")
@Tag("container")
@DisplayName("The gist, with a real model")
class LmStudioSpikeDemo {

  private static final Duration PATIENCE = Duration.ofMinutes(5);

  private static SpikeRuntime start() {
    return new LocalSpikeRuntime(
        ConfigFactory.load("spike-postgres").resolve(),
        new LmStudioSpikeModel(
            LmStudioSpikeModel.BASE_URL, LmStudioSpikeModel.MODEL_ID, "lm-studio"),
        SpikeSweep.overPostgres(LocalRestartTest.URL, "watchman", "watchman"));
  }

  private static SpikeTurnState stateOf(SpikeRuntime runtime, String agentId) {
    TestProbe<SpikeTurnState> probe = TestProbe.create(runtime.system());
    runtime.agents().tell(agentId, new AgentActor.Inspect(probe.getRef()));
    return probe.receiveMessage(Duration.ofSeconds(30));
  }

  private static void narrate(String heading, SpikeTurnState state) {
    System.out.println("\n=== " + heading + " ===");
    state.transcript().forEach(line -> System.out.println("    " + line));
    if (state instanceof SpikeTurnState.WorkingTools working) {
      working.calls().forEach(call -> System.out.println("    [call] " + call));
    }
  }

  @Test
  void a_real_model_drives_a_turn_that_parks_survives_a_restart_and_finishes() {
    String agent = "lmstudio-" + UUID.randomUUID();

    Optional<String> parkedCall;
    try (SpikeRuntime first = start()) {
      first.agents().tell(agent, new AgentActor.Observe("please tidy up"));

      await()
          .atMost(PATIENCE)
          .pollInterval(Duration.ofSeconds(1))
          .untilAsserted(
              () -> {
                SpikeTurnState state = stateOf(first, agent);
                assertThat(state).isNotInstanceOf(SpikeTurnState.CallingModel.class);
              });

      SpikeTurnState afterModel = stateOf(first, agent);
      narrate("the real model's first turn", afterModel);
      parkedCall = parkedCallIn(afterModel);
      assertThat(afterModel).isNotNull();
    }

    if (parkedCall.isEmpty()) {
      System.out.println(
          "\n=== the model asked for nothing that needed approval; nothing parked ===");
      return;
    }

    // A genuinely new actor system, which has never seen this turn.
    try (SpikeRuntime second = start()) {
      narrate("recovered from Postgres by a fresh JVM lifetime", stateOf(second, agent));

      second.agents().tell(agent, new AgentActor.AnswerApproval(parkedCall.get(), true, ""));

      await()
          .atMost(PATIENCE)
          .pollInterval(Duration.ofSeconds(1))
          .untilAsserted(
              () -> assertThat(stateOf(second, agent)).isInstanceOf(SpikeTurnState.Idle.class));

      SpikeTurnState done = stateOf(second, agent);
      narrate("the finished turn", done);
      List<String> transcript = done.transcript();
      assertThat(transcript).isNotEmpty();
      assertThat(transcript).anyMatch(line -> line.startsWith("tool: "));
      assertThat(transcript.getLast()).startsWith("assistant: ");
    }
  }

  private static Optional<String> parkedCallIn(SpikeTurnState state) {
    if (!(state instanceof SpikeTurnState.WorkingTools working)) {
      return Optional.empty();
    }
    return working.calls().stream()
        .filter(call -> !call.settled())
        .map(SpikeToolCall::id)
        .findFirst();
  }
}
