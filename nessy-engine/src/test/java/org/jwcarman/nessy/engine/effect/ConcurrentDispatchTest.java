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
package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * A batch is performed in parallel, not one row at a time.
 *
 * <p>An effect is a model call, so serial execution would make every agent in a batch wait out
 * every agent ahead of it for no reason but the order the query returned them. This is the kind of
 * thing that looks fine in a log and only shows up as latency under load, so it is asserted rather
 * than assumed: the model here refuses to answer anyone until everyone has arrived, which cannot
 * happen at all unless the calls are genuinely concurrent.
 */
// A long interval on purpose. Concurrency is a property of one batch, so this test needs all
// three observations in the table before any pass looks -- and the first pass waits a full
// interval. At a short interval a pass can land mid-setup, take one row, and block the rest
// behind it, which is correct behaviour and would fail this test for the wrong reason.
class ConcurrentDispatchTest {

  private static final AgentType CHAT = new AgentType("chat");
  private static final int AGENTS = 3;
  private static final Duration POLL = Duration.ofSeconds(3);

  private final Rendezvous model = new Rendezvous();
  private EngineFixture engine;
  private Harness<String> harness;

  @BeforeEach
  void startEngine() {
    engine = new EngineFixture(model);
    harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(CHAT)
                        .systemPrompt("You are a test assistant.")
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.maxInFlight(AGENTS).pollInterval(POLL)));
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  /** The whole record, flattened -- what was stored, not what would be sent. */
  private List<HistoryEntry> story(AgentType agentType, AgentId agentId) {
    return engine.history().entriesFrom(agentType, agentId, 0);
  }

  @Test
  void everyEffectInABatchIsPerformedAtTheSameTime() {
    List<AgentId> agents =
        java.util.stream.Stream.generate(() -> new AgentId(UUID.randomUUID()))
            .limit(AGENTS)
            .toList();

    agents.forEach(agentId -> harness.observe(agentId, "hello"));

    // Every call blocks until all three are in flight, so this only completes if the dispatcher
    // ran them concurrently. Serial execution deadlocks here until the latch times out, and
    // each answer then comes back null -- which the assertions below would catch.
    agents.forEach(
        agentId ->
            await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(
                    () ->
                        assertThat(story(CHAT, agentId))
                            .hasSize(2)
                            .last()
                            .isEqualTo(HistoryEntry.InferenceAnswered.of(2, 1, "all here"))));

    assertThat(model.everyoneArrived())
        .as("the rendezvous is the proof; without it the answers could not have been given")
        .isTrue();
  }

  /** Answers nobody until everybody has asked. */
  static class Rendezvous implements InferenceProvider {

    private final CountDownLatch arrived = new CountDownLatch(AGENTS);
    private volatile boolean everyoneArrived;

    @Override
    public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
      arrived.countDown();
      try {
        if (!arrived.await(15, TimeUnit.SECONDS)) {
          throw new IllegalStateException("effects were performed one at a time");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      everyoneArrived = true;
      return new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("all here"));
    }

    boolean everyoneArrived() {
      return everyoneArrived;
    }
  }
}
