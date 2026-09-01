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
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentSubscription;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.HarnessFactory;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.testing.TestDatabase;

/**
 * The engine through the door an application actually uses.
 *
 * <p>Nothing here mentions an actor, a shard, a persistence id, or a codec. If this passes, the API
 * is implemented rather than merely implementable — which is a different claim from anything the
 * other tests make, since they all reach past the front door to wire sharding by hand.
 */
@DisplayName("A harness, used the way an application would")
class HarnessTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final AgentId HOUSE = AgentId.of("house-12");

  private static ActorTestKit testKit;
  private static Harness<HouseEvent> harness;

  @BeforeAll
  static void wireEverything() {
    testKit = ClusterOfOne.start();

    ModelProvider models =
        id ->
            new Model() {
              @Override
              public ModelId id() {
                return id;
              }

              @Override
              public org.jwcarman.nessy.spi.model.ModelStream stream(ModelRequest request) {
                return Scripts.saying(
                    new ModelResult.Answered(
                        new AnswerMessage(
                            List.of(
                                new TextBlock(
                                    "noted: " + request.context().lines().getLast().text()))),
                        StopReason.END_TURN,
                        new Usage(10, 5)));
              }
            };

    HarnessFactory factory =
        new PekkoHarnessFactory(
            engine ->
                engine
                    .system(testKit.system())
                    .models(models)
                    .dataSource(TestDatabase.fresh())
                    .maxTokens(4096)
                    .capabilities(java.util.Set.of())
                    .blocking(Runnable::run)
                    .clock(Clock.systemUTC())
                    .replyTokens(ReplyTokens.ephemeral()));

    harness =
        factory.createHarness(
            HouseEvent.class,
            config ->
                config
                    .type(WATCHMAN)
                    .systemPrompt("You watch the house.")
                    .model(ModelId.of("claude-opus-5"))
                    .renderer(HouseEvents.RENDERER));
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  @Test
  void a_harness_knows_what_kind_of_agent_it_serves() {
    assertThat(harness.type()).isEqualTo(WATCHMAN);
  }

  @Test
  @DisplayName("tell an agent something, and watch it work")
  void an_observation_is_narrated_from_start_to_finish() {
    List<AgentEvent> heard = new CopyOnWriteArrayList<>();
    AgentSubscription subscription = harness.subscribe(HOUSE, heard::add);

    harness.observe(HOUSE, new HouseEvent("kitchen", "door opened"));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              assertThat(heard).isNotEmpty();
              assertThat(heard)
                  .extracting(event -> event.getClass().getSimpleName())
                  .containsSubsequence("TurnStarted", "Answered", "TurnEnded");
            });

    AgentEvent.Answered said =
        heard.stream()
            .filter(AgentEvent.Answered.class::isInstance)
            .map(AgentEvent.Answered.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(said.message().content())
        .containsExactly(new TextBlock("noted: kitchen: door opened"));

    subscription.close();
  }

  /**
   * A CONVERSATION, which is what every REPL and chat window actually is.
   *
   * <p>Until this existed, every observation in this suite was a FIRST one to a fresh agent — six
   * of them across four files, not one of which ever came back a second time. A green build
   * therefore said nothing at all about the second thing a person types, which is the shape of
   * literally every real session.
   */
  @Test
  @DisplayName("a second observation to the same agent gets its own turn")
  void an_agent_answers_more_than_once() {
    AgentId talkative = AgentId.of("house-14");
    List<AgentEvent> heard = new CopyOnWriteArrayList<>();
    AgentSubscription subscription = harness.subscribe(talkative, heard::add);

    harness.observe(talkative, new HouseEvent("kitchen", "door opened"));
    await().atMost(15, SECONDS).untilAsserted(() -> assertThat(endings(heard)).hasSize(1));

    harness.observe(talkative, new HouseEvent("hall", "motion"));

    await().atMost(15, SECONDS).untilAsserted(() -> assertThat(endings(heard)).hasSize(2));
    assertThat(heard)
        .filteredOn(AgentEvent.Answered.class::isInstance)
        .extracting(event -> ((AgentEvent.Answered) event).message().content())
        .containsExactly(
            List.of(new TextBlock("noted: kitchen: door opened")),
            List.of(new TextBlock("noted: hall: motion")));

    subscription.close();
  }

  private static List<AgentEvent> endings(List<AgentEvent> heard) {
    return heard.stream().filter(AgentEvent.TurnEnded.class::isInstance).toList();
  }

  @Test
  @DisplayName("the closing line reports how the turn ended and what it cost")
  void a_turn_ends_with_a_result_and_a_bill() {
    List<AgentEvent> heard = new CopyOnWriteArrayList<>();
    AgentSubscription subscription = harness.subscribe(AgentId.of("house-13"), heard::add);

    harness.observe(AgentId.of("house-13"), new HouseEvent("hall", "motion"));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () ->
                assertThat(heard).filteredOn(AgentEvent.TurnEnded.class::isInstance).isNotEmpty());

    AgentEvent.TurnEnded ended =
        heard.stream()
            .filter(AgentEvent.TurnEnded.class::isInstance)
            .map(AgentEvent.TurnEnded.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(ended.outcome()).isInstanceOf(org.jwcarman.nessy.api.TurnResult.Completed.class);
    assertThat(ended.usage()).isEqualTo(new Usage(10, 5));

    subscription.close();
  }

  /**
   * Listening from where a previous listener stopped.
   *
   * <p>The machinery for this existed from the start — a ring buffer of recent events, a cursor on
   * Subscribe, replay before the live feed — and no caller could reach it: subscribe hardcoded a
   * null cursor, so the buffer was written on every narration and read by nothing. This is the test
   * that makes it a feature rather than an intention.
   */
  @Test
  @DisplayName("a listener that comes back gets what it missed, then the live feed")
  void resubscribing_with_a_cursor_replays_the_gap() {
    AgentId agent = AgentId.of("house-15");
    List<AgentEvent> first = new CopyOnWriteArrayList<>();

    AgentSubscription listening = harness.subscribe(agent, first::add);
    harness.observe(agent, new HouseEvent("kitchen", "door opened"));
    await().atMost(15, SECONDS).untilAsserted(() -> assertThat(endings(first)).hasSize(1));
    String lastSeen = first.getLast().id();
    listening.close();

    // Missed entirely: nobody is listening while this turn runs.
    harness.observe(agent, new HouseEvent("hall", "motion"));
    await().atMost(15, SECONDS).untilAsserted(() -> assertThat(first).isNotEmpty());

    List<AgentEvent> second = new CopyOnWriteArrayList<>();
    try (AgentSubscription resumed = harness.subscribe(agent, second::add, lastSeen)) {
      await().atMost(15, SECONDS).untilAsserted(() -> assertThat(endings(second)).isNotEmpty());

      // Everything replayed is strictly newer than the cursor, and the missed turn is in it.
      assertThat(second).isNotEmpty();
      assertThat(second).allSatisfy(event -> assertThat(event.id()).isGreaterThan(lastSeen));
      assertThat(second)
          .filteredOn(AgentEvent.Answered.class::isInstance)
          .extracting(event -> ((AgentEvent.Answered) event).message().content())
          .contains(List.of(new TextBlock("noted: hall: motion")));
    }
  }

  @Test
  @DisplayName("a cursor of null starts from now, replaying nothing")
  void subscribing_without_a_cursor_replays_nothing() {
    AgentId agent = AgentId.of("house-16");
    harness.observe(agent, new HouseEvent("kitchen", "door opened"));

    List<AgentEvent> heard = new CopyOnWriteArrayList<>();
    try (AgentSubscription listening = harness.subscribe(agent, heard::add, null)) {
      assertThat(heard).isEmpty();
    }
  }
}
