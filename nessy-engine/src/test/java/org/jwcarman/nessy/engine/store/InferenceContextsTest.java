package org.jwcarman.nessy.engine.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.inference.Usage;

@DisplayName("What a model was shown")
class InferenceContextsTest {

  private static final AgentType CHAT = new AgentType("chat");

  private EngineFixture engine;
  private Harness<String> harness;

  @BeforeEach
  void startEngine() {
    engine =
        new EngineFixture(
            (request, narrator) ->
                new InferenceResult.Answer(List.of(new Block.Text("a lake monster")))
                    .withUsage(new Usage(7, 9)));
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
    engine.close();
  }

  @Test
  @DisplayName("is written down whole, call by call, with how the call came back")
  void every_call_is_recorded_as_rendered() {
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness.observe(agentId, "what is nessy?");
    awaitAnswers(agentId, 1);
    harness.observe(agentId, "and where does it live?");
    awaitAnswers(agentId, 2);

    InferenceContexts contexts = engine.harnesses().inferenceContexts();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(contexts.forAgent(CHAT, agentId))
                    .hasSize(2)
                    .allSatisfy(call -> assertThat(call.outcome()).contains("answer")));

    List<RecordedInference> calls = contexts.forAgent(CHAT, agentId);
    RecordedInference first = calls.get(0);
    RecordedInference second = calls.get(1);

    assertThat(first.request().systemPrompt().value()).isEqualTo("You are a test assistant.");
    assertThat(first.usage()).contains(new Usage(7, 9));
    assertThat(first.request().options().modelName()).isEqualTo("a-model");
    assertThat(first.request().context().turns()).hasSize(1);
    assertThat(first.turn()).isEqualTo(first.request().context().turns().getLast().id());
    assertThat(first.completed()).isTrue();

    // The second call saw the first turn, answered, and the new one open.
    assertThat(second.request().context().turns()).hasSize(2);
    assertThat(second.requestedAt()).isAfterOrEqualTo(first.requestedAt());
    assertThat(contexts.find(second.id())).contains(second);
  }

  private void awaitAnswers(AgentId agentId, int answers) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(engine.history().entriesFrom(CHAT, agentId, 0))
                    .filteredOn(HistoryEntry.InferenceAnswered.class::isInstance)
                    .hasSize(answers));
  }

  @Test
  @DisplayName("can be switched off, and then nothing is written down")
  void recording_can_be_switched_off() {
    AgentId agentId = new AgentId(UUID.randomUUID());
    try (var quiet =
        new org.jwcarman.nessy.engine.harness.DefaultHarnessFactory(
            settings ->
                settings
                    .dataSource(engine.dataSource())
                    .inference(
                        (request, narrator) ->
                            new InferenceResult.Answer(List.of(new Block.Text("shh"))),
                        org.jwcarman.nessy.spi.inference.InferenceOptions.of("a-model"))
                    .recordInferenceContexts(false))) {
      Harness<String> silent =
          quiet.create(
              config ->
                  config
                      .agentType(new AgentType("quiet"))
                      .systemPrompt("You are a test assistant.")
                      .effects(e -> e.pollInterval(Duration.ofMillis(100))));
      silent.observe(agentId, "anything");
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(
                          quiet.histories().forAgent(new AgentType("quiet"), agentId).turnsFrom(0))
                      .anyMatch(org.jwcarman.nessy.api.turn.Turn::complete));
      assertThat(quiet.inferenceContexts().forAgent(new AgentType("quiet"), agentId)).isEmpty();
    }
  }
}
