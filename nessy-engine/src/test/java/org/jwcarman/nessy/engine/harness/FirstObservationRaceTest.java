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
package org.jwcarman.nessy.engine.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/**
 * An agent that does not exist yet has no row to lock, so the first fold is the one place the row
 * lock cannot serialise anything. Two first observations arriving together used to both find no row
 * and both insert; the loser died on the primary key and its observation died with it.
 */
class FirstObservationRaceTest {

  private static final AgentType CHAT = new AgentType("chat");
  private static final int CALLERS = 8;

  private EngineFixture engine;
  private Harness<String> harness;
  private final ExecutorService callers = Executors.newFixedThreadPool(CALLERS);

  @BeforeEach
  void startEngine() {
    engine =
        new EngineFixture(
            (request, narrator) ->
                new InferenceResult.Answer(List.of(new Block.Text("a lake monster"))));
    harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(CHAT)
                        .systemPrompt("You are a test assistant.")
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));
  }

  @AfterEach
  void stopEngine() {
    callers.shutdownNow();
    engine.close();
  }

  @Test
  void everyOneOfSeveralSimultaneousFirstObservationsIsKept() throws Exception {
    AgentId agentId = new AgentId(UUID.randomUUID());
    CountDownLatch go = new CountDownLatch(1);

    List<Future<Object>> told =
        IntStream.range(0, CALLERS)
            .mapToObj(
                i ->
                    callers.submit(
                        () -> {
                          go.await();
                          harness.observe(agentId, "hello " + i);
                          return null;
                        }))
            .toList();
    go.countDown();
    for (Future<Object> call : told) {
      // A caller that lost the race used to get a DuplicateKeyException here.
      call.get();
    }

    // One took the turn, the rest joined the backlog, and every one is answered in time. Waited
    // for to the last answer, not the last observation: the engines in this suite share one
    // database and one agent type, so work left in flight here would be picked up by the next
    // test's dispatcher and performed by ITS model.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              List<HistoryEntry> story = engine.history().entriesFrom(CHAT, agentId, 0);
              assertThat(story)
                  .filteredOn(HistoryEntry.ObservationReceived.class::isInstance)
                  .hasSize(CALLERS);
              assertThat(story)
                  .filteredOn(HistoryEntry.InferenceAnswered.class::isInstance)
                  .hasSize(CALLERS);
            });
  }
}
