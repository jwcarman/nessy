package org.jwcarman.nessy.engine.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * The whole loop, closed: an observation goes in one door, and an answer the model gave comes back
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
  private Harness<String> harness;

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
                String.class,
                config ->
                    config
                        .agentType(CHAT)
                        .systemPrompt("You are a test assistant.")
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  private static HistoryEntry.ObservationReceived observed(long seq, String text) {
    return HistoryEntry.ObservationReceived.opening(
        seq, HistoryEntry.ObservationReceived.text(text));
  }

  /** The whole record, flattened -- what was stored, not what would be sent. */
  private List<HistoryEntry> story(AgentType agentType, AgentId agentId) {
    return engine.history().entriesFrom(agentType, agentId, 0);
  }

  @Test
  void anObservationBecomesAModelCallAndTheAnswerLandsInTheStory() {
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness.observe(agentId, "what is nessy?");

    // The observation is recorded and the model call owed in the same transaction as the
    // state, so the story shows the question before anything has been asked.
    assertThat(story(CHAT, agentId)).containsExactly(observed(1, "what is nessy?"));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(story(CHAT, agentId))
                    .containsExactly(
                        observed(1, "what is nessy?"),
                        HistoryEntry.InferenceAnswered.of(2, 1, "a lake monster")));

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
                          assertThat(turn.observation().blocks())
                              .containsExactly(new Block.Text("what is nessy?"));
                        }));
  }

  /**
   * A second observation arriving while the first turn is still open waits in the backlog, and
   * opens its own turn as that one closes -- so the story ends with both questions answered, in the
   * order they were asked.
   */
  @Test
  void twoObservationsAreAnsweredInOrder() {
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness.observe(agentId, "first");
    harness.observe(agentId, "second");

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(story(CHAT, agentId))
                    .containsExactly(
                        observed(1, "first"),
                        HistoryEntry.InferenceAnswered.of(2, 1, "a lake monster"),
                        observed(3, "second"),
                        HistoryEntry.InferenceAnswered.of(4, 3, "a lake monster")));
  }

  /** A model that always answers the same thing, and remembers what it was asked. */
  static class RecordingModel implements InferenceProvider {

    private final List<List<Turn>> asked = new CopyOnWriteArrayList<>();

    @Override
    public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
      asked.add(List.copyOf(request.context().turns()));
      return new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("a lake monster"));
    }

    List<List<Turn>> asked() {
      return asked;
    }
  }
}
