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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * Words painted as they arrive.
 *
 * <p>The reason streaming is the primitive rather than a convenience: a chat interface shows text
 * while the model is still producing it. What reaches the transcript is a different thing — one
 * settled block, not one per network packet — and both have to be true at once.
 */
@DisplayName("A model streaming its answer")
class StreamingNarrationTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final AgentId HOUSE = AgentId.of("house-12");

  private static ActorTestKit testKit;
  private static Harness<HouseEvent> harness;

  @BeforeAll
  static void wire() {
    testKit = ClusterOfOne.start();
    ModelProvider models =
        id ->
            new Model() {
              @Override
              public ModelId id() {
                return id;
              }

              @Override
              public ModelStream stream(ModelRequest request) {
                List<ModelEvent> events =
                    List.of(
                        new ModelEvent.TextChunk("all "),
                        new ModelEvent.TextChunk("is "),
                        new ModelEvent.TextChunk("well"),
                        new ModelEvent.Stopped(StopReason.END_TURN, new Usage(3, 3)));
                return new ModelStream() {
                  @Override
                  public Iterator<ModelEvent> iterator() {
                    return events.iterator();
                  }

                  @Override
                  public void close() {
                    // nothing to release
                  }
                };
              }
            };

    harness =
        new PekkoHarnessFactory(
                testKit.system(),
                new InMemorySubstrate(Clock.systemUTC()),
                models,
                4096,
                java.util.Set.of(),
                Runnable::run,
                Clock.systemUTC(),
                ReplyTokens.ephemeral())
            .createHarness(
                HouseEvent.class,
                config ->
                    config
                        .type(WATCHMAN)
                        .systemPrompt("You watch the house.")
                        .model(ModelId.of("scripted"))
                        .renderer(HouseEvents.RENDERER));
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  @Test
  @DisplayName("every chunk is narrated, and the settled message is one block")
  void deltas_are_painted_but_the_transcript_gets_a_sentence() {
    List<AgentEvent> heard = new CopyOnWriteArrayList<>();
    harness.subscribe(HOUSE, heard::add);

    harness.observe(HOUSE, new HouseEvent("kitchen", "quiet"));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () ->
                assertThat(heard).filteredOn(AgentEvent.TurnEnded.class::isInstance).isNotEmpty());

    assertThat(heard)
        .filteredOn(AgentEvent.TextDelta.class::isInstance)
        .extracting(event -> ((AgentEvent.TextDelta) event).text())
        .containsExactly("all ", "is ", "well");

    AgentEvent.Answered said =
        heard.stream()
            .filter(AgentEvent.Answered.class::isInstance)
            .map(AgentEvent.Answered.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(said.message().content()).containsExactly(new TextBlock("all is well"));
  }

  @Test
  void the_deltas_arrive_before_the_settled_message() {
    List<AgentEvent> heard = new CopyOnWriteArrayList<>();
    harness.subscribe(AgentId.of("house-13"), heard::add);

    harness.observe(AgentId.of("house-13"), new HouseEvent("hall", "quiet"));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> assertThat(heard).filteredOn(AgentEvent.Answered.class::isInstance).isNotEmpty());

    List<String> order = heard.stream().map(event -> event.getClass().getSimpleName()).toList();
    assertThat(order).containsSubsequence("TextDelta", "TextDelta", "TextDelta", "Answered");
  }
}
