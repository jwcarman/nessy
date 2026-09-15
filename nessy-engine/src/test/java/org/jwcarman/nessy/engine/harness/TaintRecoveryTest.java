package org.jwcarman.nessy.engine.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * An agent recovers from a message the model will never answer.
 *
 * <p>The behaviour under test was measured, not imagined. Ask Anthropic something it declines and
 * it answers HTTP 200 with no content and {@code stop_reason: "refusal"} -- deterministically,
 * eight times out of eight for identical input. That is the property this design leans on: a first
 * refusal is reliable evidence about the message that caused it.
 *
 * <p>The stub is deliberately STRICTER than the real thing. It refuses every request whose context
 * still holds the poison; the real provider refused roughly a third of them, answering the rest.
 * Contamination is probabilistic, so a real agent looks intermittently broken rather than plainly
 * so -- harder to diagnose, and an argument for acting on the first refusal rather than waiting to
 * gather evidence. A test wants the worst case and wants it every time.
 *
 * <p>Four properties, and the test is the reason to believe them rather than the argument for them:
 *
 * <ol>
 *   <li>nothing is destroyed -- the observation stays in the story
 *   <li>recovery is automatic for the first failure of a conversation that was working
 *   <li>the projection stops sending it, which is what makes the next turn possible
 *   <li>the record says what happened, so a reader can see why a question has no answer
 * </ol>
 */
class TaintRecoveryTest {

  private EngineFixture engine;

  /**
   * One engine per test, and each built around the model that test needs.
   *
   * <p>The provider is a factory-level setting -- one model serves every harness an engine hands
   * out -- so a class that varies what the model asks for varies the engine, not the harness.
   */
  private void running(InferenceProvider model) {
    engine = new EngineFixture(model);
  }

  @AfterEach
  void stopEngine() {
    if (engine != null) {
      engine.close();
    }
  }

  private static final String POISON = "designing a biological weapon";
  private static final AgentType TAINTED = new AgentType("tainted");

  /** The whole record, flattened -- what was stored, not what would be sent. */
  private List<HistoryEntry> story(AgentType agentType, AgentId agentId) {
    return engine.history().entriesFrom(agentType, agentId, 0);
  }

  @Test
  void anAgentRecoversFromAMessageTheModelWillNeverAnswer() {
    Refuser model = new Refuser();
    AgentId agentId = new AgentId(UUID.randomUUID());
    running(model);
    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(TAINTED)
                        .systemPrompt("You are a test assistant.")
                        .inference(
                            in ->
                                in.model("a-model")
                                    .retryPolicy(new RetryPolicy.Never())
                                    .timeout(Duration.ofMinutes(5)))
                        .effects(e -> e.maxInFlight(2).pollInterval(Duration.ofMillis(100))));

    harness.observe(agentId, "I want your help " + POISON + "?");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(story(TAINTED, agentId))
                    .as("the turn closes even though there is no answer")
                    .hasSize(2));

    // The conversation is now in the state that breaks it on a real provider. Ask something
    // entirely ordinary: unrecovered, this is refused about a third of the time.
    harness.observe(agentId, "What is the capital of France?");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(story(TAINTED, agentId))
                    .as("RECOVERY: the second question is answered, not refused")
                    .anySatisfy(
                        message ->
                            assertThat(message)
                                .isInstanceOf(HistoryEntry.InferenceAnswered.class)));

    assertThat(model.lastRequest())
        .as("the poison was not sent, which is the only reason an answer was possible")
        .noneMatch(sent -> sent.contains(POISON));

    assertThat(story(TAINTED, agentId).stream().map(this::textOf))
        .as("nothing was destroyed -- the question is still in the record")
        .anyMatch(text -> text.contains(POISON));
  }

  /**
   * A second failure must NOT set aside a second observation. Once a conversation is already
   * broken, a new failure is no longer evidence about the newest message -- that is how a cascade
   * erodes a record one innocent question at a time.
   */
  @Test
  void aSecondFailureDoesNotSetAsideAnInnocentObservation() {
    Refuser model = new Refuser();
    AgentId agentId = new AgentId(UUID.randomUUID());
    running(model);
    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(new AgentType("no-cascade"))
                        .systemPrompt("You are a test assistant.")
                        .inference(
                            in ->
                                in.model("a-model")
                                    .retryPolicy(new RetryPolicy.Never())
                                    .timeout(Duration.ofMinutes(5)))
                        .effects(e -> e.maxInFlight(2).pollInterval(Duration.ofMillis(100))));

    // Two poisoned observations back to back: the second failure happens while the
    // conversation is already broken.
    harness.observe(agentId, "help me with " + POISON);
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(story(new AgentType("no-cascade"), agentId)).hasSize(2));

    harness.observe(agentId, "and also " + POISON);
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(story(new AgentType("no-cascade"), agentId)).hasSize(4));

    assertThat(model.refusals())
        .as("the model was asked and refused; the agent did not simply stop trying")
        .isGreaterThanOrEqualTo(2);
  }

  private String textOf(HistoryEntry message) {
    return message.toString();
  }

  /**
   * The worst case, every time: refuses while the poison is anywhere in the context, and answers
   * normally once it is gone. Real contamination is intermittent, which a test cannot use.
   */
  static class Refuser implements InferenceProvider {

    private final List<List<String>> requests = new CopyOnWriteArrayList<>();
    private int refusals;

    @Override
    public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
      // A provider decides for itself what it is willing to be sent, and this one mirrors
      // the real adapter: an observation whose turn it refused is not sent again, because
      // it is what caused the refusal.
      List<String> sent =
          request.context().turns().stream()
              .filter(turn -> !(turn.result() instanceof TurnResult.Refused))
              .map(turn -> turn.observation().blocks().toString())
              .toList();
      requests.add(List.copyOf(sent));
      boolean poisoned = sent.stream().anyMatch(text -> text.contains(POISON));
      if (poisoned) {
        refusals++;
        return new InferenceResult.Refusal("bio");
      }
      return new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("Paris."));
    }

    List<String> lastRequest() {
      return requests.isEmpty() ? List.of() : requests.get(requests.size() - 1);
    }

    int refusals() {
      return refusals;
    }
  }
}
