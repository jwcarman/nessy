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
package org.jwcarman.nessy.examples.watchman.pekko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * THE WHOLE THING, with a real model: a round against LM Studio, a proposal parked for a human, a
 * DENIAL through the same door the page uses, and the round finishing on a different actor system.
 *
 * <pre>
 *   ./mvnw -pl nessy-examples/watchman-pekko test -Dnessy.excludedGroups= -Dtest=LmStudioRoundDemo
 * </pre>
 *
 * <p>Needs LM Studio serving {@code qwen/qwen3.6-35b-a3b} and the {@code watchman_pekko} schema.
 * Asserts loosely on purpose: a real model decides for itself which tools to call, and what it
 * actually does is the observation this demo exists to produce.
 */
@Tag("live")
@Tag("container")
@DisplayName("A real watchman round")
class LmStudioRoundDemo {

  private static final Duration PATIENCE = Duration.ofMinutes(5);

  private static WatchmanActorSystem start() {
    WatchmanActorSystem actors =
        new WatchmanActorSystem(
            WatchmanPostgres.config(),
            new ProviderWatchmanModel(
                org.jwcarman.nessy.model.openai.OpenAiModelProvider.create(
                        c -> c.apiKey("lm-studio").baseUrl("http://localhost:1234/v1"))
                    .model("qwen/qwen3.6-35b-a3b"),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                4096),
            new FakeRunner(),
            WatchmanPostgres.memories(),
            MicrometerTracing.noop(),
            Clock.systemUTC(),
            new BlockingWork(),
            Duration.ofMinutes(10),
            Duration.ofSeconds(30));
    actors.start();
    return actors;
  }

  private static TurnState state(WatchmanActorSystem actors, String agent) {
    try {
      return actors.inspect(agent).toCompletableFuture().get(60, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void narrate(String heading, String agent, TurnState state) {
    System.out.println("\n=== " + heading + " ===");
    // Context.lines() is Nessy's own rendering; no transcript type of ours is involved.
    for (var line : WatchmanPostgres.memories().everything(agent).lines()) {
      System.out.println(
          String.format("  %-9s | %s", line.role(), line.text().replace("\n", "\n            | ")));
    }
    if (state instanceof TurnState.WorkingTools working) {
      working.calls().forEach(call -> System.out.println("  [call] " + call));
    }
  }

  @Test
  void a_real_round_proposes_something_parks_survives_a_restart_and_is_denied() throws Exception {
    String agent = "lmstudio-" + UUID.randomUUID();

    Optional<String> parked;
    WatchmanActorSystem first = start();
    try {
      WatchmanPostgres.observe(agent, "It is " + Clock.systemUTC().instant() + ". Do your rounds.");
      first.tell(
          agent,
          new AgentActor.Observe(
              "It is " + Clock.systemUTC().instant() + ". Do your rounds.",
              "rounds",
              java.util.Map.of()));

      await()
          .atMost(PATIENCE)
          .pollInterval(Duration.ofSeconds(2))
          .untilAsserted(
              () -> assertThat(state(first, agent)).isNotInstanceOf(TurnState.CallingModel.class));

      TurnState afterModel = state(first, agent);
      narrate("the real model's first turn", agent, afterModel);
      parked = parkedCall(afterModel);
    } finally {
      first.stop();
    }

    if (parked.isEmpty()) {
      System.out.println("\n=== the model proposed nothing that needed a human ===");
      return;
    }

    // A genuinely new actor system, which has never seen this round.
    WatchmanActorSystem second = start();
    try {
      var pending =
          new PendingApprovals(WatchmanPostgres.dataSource(), Clock.systemUTC()).pending();
      System.out.println("\n=== the approvals page, after a restart ===");
      pending.forEach(row -> System.out.println("  " + row.action() + "  (" + row.dwell() + ")"));
      assertThat(pending).anySatisfy(row -> assertThat(row.agentId()).isEqualTo(agent));

      AgentActor.Ack ack =
          second
              .answerApproval(agent, parked.get(), false, "james", "not on a production box")
              .toCompletableFuture()
              .get(60, TimeUnit.SECONDS);
      assertThat(ack.accepted()).isTrue();

      await()
          .atMost(PATIENCE)
          .pollInterval(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(state(second, agent)).isInstanceOf(TurnState.Idle.class));

      TurnState done = state(second, agent);
      narrate("the finished round", agent, done);
      var messages = WatchmanPostgres.memories().everything(agent).messages();
      assertThat(messages).isNotEmpty();
      assertThat(WatchmanPostgres.results(agent)).isNotEmpty();
      assertThat(messages.getLast().role())
          .isEqualTo(org.jwcarman.nessy.api.message.Role.ASSISTANT);
    } finally {
      second.stop();
    }
  }

  private static Optional<String> parkedCall(TurnState state) {
    if (!(state instanceof TurnState.WorkingTools working)) {
      return Optional.empty();
    }
    return working.calls().stream()
        .filter(call -> WatchmanTools.needsApproval(call.tool()))
        .filter(call -> !call.decided() && !call.settled())
        .map(ToolCallRecord::id)
        .findFirst();
  }
}
