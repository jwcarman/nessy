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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.inmemory.InMemoryQueuedBackend;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole loop, closed: an input goes in one door, and an answer the model gave comes back
 * through the other and lands in the story.
 *
 * <p>Nothing here drives a dispatcher by hand. The point is that the harness polls on its own -- so
 * this is the one test that lets the schedule run, at an interval short enough to wait on.
 *
 * <p>The model is a stub written by hand rather than a mocking library: what is needed is a model
 * that answers and records what it was asked, and that is nine lines.
 */
class HarnessLoopTest {

  private static final AgentType CHAT = new AgentType("chat");

  private final RecordingModel model = new RecordingModel();
  private EngineFixture engine;
  private QueuedHarness<String> harness;

  /**
   * The model belongs to the engine and the harness to the agent type, which is the whole of what
   * an application configures. Built here rather than injected, so this test carries no application
   * of its own.
   */
  @BeforeEach
  void startEngine() {
    engine = new EngineFixture(model);
    harness =
        engine
            .harnesses()
            .create(
                CHAT,
                String.class,
                config ->
                    config
                        .systemPrompt("You are a test assistant.")
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  private AgentEvent.TurnStarted observed(AgentId agent, long seq, String text) {
    return engine.turnStarted(agent, seq, text);
  }

  /** The whole record, flattened -- what was stored, not what would be sent. */
  private List<AgentEvent> story(AgentType agentType, AgentId agentId) {
    return engine.story(agentType, agentId);
  }

  @Test
  void anInputBecomesAModelCallAndTheAnswerLandsInTheStory() {
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness.tell(agentId, "what is nessy?");

    // The input is recorded and the model call owed in the same transaction as the
    // state, so the story shows the question before anything has been asked.
    assertThat(story(CHAT, agentId))
        .usingRecursiveFieldByFieldElementComparator(EngineFixture.ignoringWhenItStarted())
        .containsExactly(observed(agentId, 1, "what is nessy?"));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(story(CHAT, agentId))
                    .usingRecursiveFieldByFieldElementComparator(
                        EngineFixture.ignoringWhenItStarted())
                    .containsExactly(
                        observed(agentId, 1, "what is nessy?"),
                        engine.answered(agentId, 2, 1, "a lake monster")));

    assertThat(model.asked())
        .as("the call is built from the story: one turn, still open, carrying the question")
        .singleElement()
        .satisfies(
            turns ->
                assertThat(turns)
                    .singleElement()
                    .satisfies(
                        turn -> {
                          assertThat(turn.id()).isEqualTo(new TurnId(1));
                          assertThat(turn.complete())
                              .as("the turn being asked about has no result yet")
                              .isFalse();
                          assertThat(turn.input().blocks())
                              .containsExactly(new Block.Text("what is nessy?"));
                        }));
  }

  /**
   * A second input arriving while the first turn is still open waits in the backlog, and opens its
   * own turn as that one closes -- so the story ends with both questions answered, in the order
   * they were asked.
   */
  @Test
  void twoInputsAreAnsweredInOrder() {
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness.tell(agentId, "first");
    harness.tell(agentId, "second");

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(story(CHAT, agentId))
                    .usingRecursiveFieldByFieldElementComparator(
                        EngineFixture.ignoringWhenItStarted())
                    .containsExactly(
                        observed(agentId, 1, "first"),
                        engine.answered(agentId, 2, 1, "a lake monster"),
                        observed(agentId, 3, "second"),
                        engine.answered(agentId, 4, 3, "a lake monster")));
  }

  /** A model that always answers the same thing, and remembers what it was asked. */
  static class RecordingModel implements InferenceProvider {

    private final List<List<Turn>> asked = new CopyOnWriteArrayList<>();

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      asked.add(List.copyOf(request.context().turns()));
      return new InferenceResult.Answer(List.of(new Block.Text("a lake monster")));
    }

    List<List<Turn>> asked() {
      return asked;
    }
  }

  /**
   * The registry, on this door -- built over an in-memory backend rather than {@link
   * EngineFixture}'s Postgres, so a factory holding two named providers (or none) needs nothing
   * this process does not already have.
   */
  @Nested
  class AFactoryHoldsItsProvidersByName {

    private static AgentType type(String suffix) {
      return new AgentType("chat-" + suffix);
    }

    private static InferenceProvider answering(String said) {
      return (request, narrator) -> new InferenceResult.Answer(List.of(new Block.Text(said)));
    }

    private DefaultQueuedHarnessFactory factoryOf(
        Customizer<QueuedHarnessFactoryConfig> customizer) {
      return DefaultQueuedHarnessFactory.of(
          config -> {
            config.backend(
                new InMemoryQueuedBackend(new JacksonCodecFactory(JsonMapper.builder().build())));
            customizer.customize(config);
          });
    }

    /** The finished turn's own words, or empty while it is still in flight. */
    private static Optional<String> answerOf(
        DefaultQueuedHarnessFactory factory, AgentType type, AgentId agent) {
      return factory.histories().forAgent(type, agent).lastTurns(1).stream()
          .filter(Turn::complete)
          .map(Turn::result)
          .flatMap(
              result ->
                  result instanceof TurnResult.Answered answered
                      ? answered.blocks().stream()
                      : Stream.empty())
          .filter(Block.Text.class::isInstance)
          .map(Block.Text.class::cast)
          .map(Block.Text::text)
          .findFirst();
    }

    @Test
    void an_agent_type_naming_a_registered_id_is_answered_by_that_provider() {
      AgentType type = type("named");
      try (DefaultQueuedHarnessFactory factory =
          factoryOf(
              config ->
                  config
                      .provider(ProviderId.of("first"), answering("first"))
                      .provider(ProviderId.of("second"), answering("second")))) {
        QueuedHarness<String> harness =
            factory.create(
                type,
                String.class,
                config ->
                    config
                        .systemPrompt("terse")
                        .inference(in -> in.provider("second").model("m"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(20))));
        AgentId agent = new AgentId(UUID.randomUUID());

        harness.tell(agent, "hello");

        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(answerOf(factory, type, agent)).contains("second"));
      }
    }

    @Test
    void an_agent_type_naming_nothing_is_answered_by_the_default() {
      AgentType type = type("default");
      try (DefaultQueuedHarnessFactory factory =
          factoryOf(
              config ->
                  config
                      .provider(ProviderId.of("first"), answering("first"))
                      .provider(ProviderId.of("second"), answering("second"))
                      .inference(ProviderId.of("first"), InferenceOptions.of("m")))) {
        QueuedHarness<String> harness =
            factory.create(
                type,
                String.class,
                config ->
                    config
                        .systemPrompt("terse")
                        .effects(e -> e.pollInterval(Duration.ofMillis(20))));
        AgentId agent = new AgentId(UUID.randomUUID());

        harness.tell(agent, "hello");

        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(answerOf(factory, type, agent)).contains("first"));
      }
    }

    @Test
    void an_agent_type_naming_an_unknown_provider_fails_when_the_harness_is_built() {
      AgentType type = type("unknown");
      DefaultQueuedHarnessFactory factory =
          factoryOf(config -> config.provider(ProviderId.of("first"), answering("first")));

      assertThatThrownBy(
              () ->
                  factory.create(
                      type,
                      String.class,
                      config ->
                          config
                              .systemPrompt("terse")
                              .inference(in -> in.provider("claude").model("m"))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "agent type 'chat-unknown' names provider 'claude', which is not registered;"
                  + " registered: [first]");
    }

    @Test
    void no_model_and_no_default_fails_naming_the_agent_type() {
      AgentType type = type("no-model");
      DefaultQueuedHarnessFactory factory =
          factoryOf(config -> config.provider(ProviderId.of("first"), answering("first")));

      assertThatThrownBy(
              () ->
                  factory.create(
                      type,
                      String.class,
                      config -> config.systemPrompt("terse").inference(in -> in.provider("first"))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("agent type 'chat-no-model' names no model and the factory has no default");
    }

    @Test
    void a_factory_default_with_no_max_tokens_still_builds_with_the_4096_ceiling() {
      AgentType type = type("no-max-tokens");
      AtomicInteger seenMaxTokens = new AtomicInteger();
      InferenceProvider recordingMaxTokens =
          (request, narrator) -> {
            seenMaxTokens.set(request.options().maxTokens());
            return new InferenceResult.Answer(List.of(new Block.Text("first")));
          };
      try (DefaultQueuedHarnessFactory factory =
          factoryOf(
              config ->
                  config
                      .provider(ProviderId.of("first"), recordingMaxTokens)
                      .inference(ProviderId.of("first"), InferenceOptions.of("m")))) {
        QueuedHarness<String> harness =
            factory.create(
                type,
                String.class,
                config ->
                    config
                        .systemPrompt("terse")
                        .effects(e -> e.pollInterval(Duration.ofMillis(20))));
        AgentId agent = new AgentId(UUID.randomUUID());

        harness.tell(agent, "hello");

        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(answerOf(factory, type, agent)).contains("first"));
        assertThat(seenMaxTokens.get()).isEqualTo(4096);
      }
    }
  }
}
