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
package org.jwcarman.nessy.engine.harness.queued;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.TellOutcome;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * A tell and a terminate for one agent at the same moment: whichever takes the agent's lock first
 * decides, and the outcome the tell answered always agrees with what was stored.
 */
@Tag("container")
class TellTerminateRaceTest {

  private static final Duration PATIENT = Duration.ofSeconds(20);
  private static final int ROUNDS = 40;

  /** Holds every model call on a "held" agent until released. */
  private static volatile CountDownLatch release = new CountDownLatch(0);

  private static volatile CountDownLatch entered = new CountDownLatch(0);

  private static final InferenceProvider MODEL =
      (request, _) -> {
        if (request.systemPrompt().value().startsWith("held")) {
          entered.countDown();
          try {
            if (!release.await(30, TimeUnit.SECONDS)) {
              throw new IllegalStateException("never released");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        }
        return new InferenceResult.Answer(List.of(new Block.Text("all done")));
      };

  private static EngineFixture engine;

  private final ExecutorService threads = Executors.newFixedThreadPool(2);

  @BeforeAll
  static void startEngine() {
    engine = new EngineFixture(MODEL);
  }

  @AfterAll
  static void stopEngine() {
    engine.close();
  }

  @AfterEach
  void letGo() {
    release.countDown();
    threads.shutdownNow();
  }

  private static QueuedHarness<String> harness(String story) {
    return engine
        .harnesses()
        .create(
            new AgentType(story),
            String.class,
            config ->
                config
                    .systemPrompt(story)
                    .inference(in -> in.model("a-model"))
                    .effects(e -> e.pollInterval(Duration.ofMillis(50))));
  }

  private static void awaitStatus(String story, AgentId agent, Activity activity) {
    await()
        .atMost(PATIENT)
        .untilAsserted(
            () ->
                assertThat(engine.work().status(new AgentType(story), agent).activity())
                    .isEqualTo(activity));
  }

  private static long turnsStarted(String story, AgentId agent) {
    return engine.story(new AgentType(story), agent).stream()
        .filter(AgentEvent.TurnStarted.class::isInstance)
        .count();
  }

  @Test
  void a_tell_racing_a_terminate_answers_what_was_stored() throws Exception {
    String story = "race";
    QueuedHarness<String> harness = harness(story);
    for (int round = 0; round < ROUNDS; round++) {
      AgentId agent = AgentId.random();
      CountDownLatch go = new CountDownLatch(1);
      CompletableFuture<TellOutcome> told =
          CompletableFuture.supplyAsync(
              () -> {
                awaitQuietly(go);
                return harness.tell(agent, "go");
              },
              threads);
      CompletableFuture<Void> terminated =
          CompletableFuture.runAsync(
              () -> {
                awaitQuietly(go);
                harness.terminate(agent);
              },
              threads);
      go.countDown();
      TellOutcome outcome = told.get(20, TimeUnit.SECONDS);
      terminated.get(20, TimeUnit.SECONDS);
      awaitStatus(story, agent, Activity.ENDED);

      if (outcome instanceof TellOutcome.Accepted) {
        assertThat(turnsStarted(story, agent)).as("round %d: accepted, so it ran", round).isOne();
      } else {
        assertThat(engine.story(new AgentType(story), agent))
            .as("round %d: terminated, so the tell wrote nothing", round)
            .map(event -> event.getClass().getSimpleName())
            .containsExactly("Terminated");
        assertThat(engine.work().status(new AgentType(story), agent).queued()).isZero();
      }
    }
  }

  @Test
  void an_input_accepted_while_a_turn_runs_is_abandoned_by_a_terminate_that_follows() {
    String story = "held-then-terminated";
    QueuedHarness<String> harness = harness(story);
    AgentId agent = AgentId.random();
    release = new CountDownLatch(1);
    entered = new CountDownLatch(1);
    harness.tell(agent, "first");
    await().atMost(PATIENT).until(() -> entered.getCount() == 0);

    TellOutcome outcome = harness.tell(agent, "second");
    harness.terminate(agent);
    release.countDown();

    assertThat(outcome).isEqualTo(new TellOutcome.Accepted());
    awaitStatus(story, agent, Activity.ENDED);
    assertThat(turnsStarted(story, agent)).as("the accepted second input never ran").isOne();
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
