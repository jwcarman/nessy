package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * A deadline is the agent saying how long it is willing to wait, and it is measured from the moment
 * the effect is written down. Two things follow, and neither of them is "try harder".
 */
class DeadlineTest {

  private EngineFixture engine;

  /**
   * One engine per test, and each built around the model that test needs.
   *
   * <p>The provider is a factory-level setting -- one model serves every harness an engine hands
   * out -- so a class that varies what the model does varies the engine, not the harness.
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

  private Harness<String> harness(
      AgentType agentType, Duration timeout, RetryPolicy policy, InferenceProvider model) {
    running(model);
    return engine
        .harnesses()
        .create(
            String.class,
            config ->
                config
                    .agentType(agentType)
                    .systemPrompt("You are a test assistant.")
                    .inference(in -> in.model("a-model").retryPolicy(policy).timeout(timeout))
                    .effects(e -> e.maxInFlight(2).pollInterval(Duration.ofMillis(100))));
  }

  /**
   * The work is never attempted at all.
   *
   * <p>A deadline measured from emit can pass while the row is still queued, and when it does there
   * is nothing to gain by calling the model: the agent stopped being willing to wait before anyone
   * picked the work up. What it is owed is the answer that it is not coming, and that answer was
   * written beside the effect when the effect was.
   */
  @Test
  void anEffectPastItsDeadlineIsNeverPerformed() {
    AgentType type = new AgentType("expires-first");
    AgentId agentId = new AgentId(UUID.randomUUID());
    CountingModel model = new CountingModel();
    // Shorter than the poll interval, so the deadline is behind us before the first claim.
    Harness<String> harness = harness(type, Duration.ofMillis(1), new RetryPolicy.Never(), model);

    harness.observe(agentId, "too late already");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(agentId))
                  .as("the turn ended, so the agent is not left waiting on a call")
                  .isEqualTo("Idle");
              assertThat(outstandingEffects(agentId))
                  .as("nothing left holding an obligation nobody will discharge")
                  .isZero();
            });

    assertThat(model.calls())
        .as("expired means not worth doing -- the provider is never asked")
        .isZero();
    assertThat(story(type, agentId))
        .as("the turn is closed by the failure stored beside the effect")
        .containsExactly(
            HistoryEntry.ObservationReceived.opening(
                1, HistoryEntry.ObservationReceived.text("too late already")),
            new HistoryEntry.InferenceFailed(new Seq(2), new TurnId(1)));
  }

  /**
   * A backoff with nowhere to land is a give-up, not a later attempt.
   *
   * <p>The policy here would happily allow three tries, but the first failure comes back inside a
   * budget far shorter than its backoff. Waiting the backoff would take the agent past the deadline
   * it set, so the attempt that follows would be one nobody asked for. Without the clamp this
   * reschedules and the agent waits out the whole backoff to be told what was already known --
   * which is what the timeout on this assertion would catch.
   */
  @Test
  void aBackoffPastTheDeadlineGivesUpInsteadOfRetrying() {
    AgentType type = new AgentType("backs-off-too-far");
    AgentId agentId = new AgentId(UUID.randomUUID());
    AlwaysBroken model = new AlwaysBroken();
    Harness<String> harness =
        harness(
            type,
            Duration.ofSeconds(3),
            new RetryPolicy.FixedDelay(3, Duration.ofMinutes(10), Duration.ZERO),
            model);

    harness.observe(agentId, "will not work");

    // Both conditions inside one await, because they are reached by two commits rather than
    // one: the fold writes the state, and the dispatcher deletes the row it finished with.
    // Between those, Idle is visible and the row is still there -- so asserting the count
    // after the state settles is asserting into a window that is legitimately open.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(agentId))
                  .as("the turn ended rather than waiting out a ten-minute backoff")
                  .isEqualTo("Idle");
              assertThat(outstandingEffects(agentId))
                  .as("nothing left holding an obligation nobody will discharge")
                  .isZero();
            });

    assertThat(model.calls())
        .as("one attempt: the policy allowed three, the deadline allowed one")
        .isEqualTo(1);
  }

  private List<HistoryEntry> story(AgentType agentType, AgentId agentId) {
    return engine.history().entriesFrom(agentType, agentId, 0);
  }

  private String agentStateOf(AgentId agentId) {
    return engine
        .jdbc()
        .sql("SELECT state_type FROM nessy_agent_state WHERE agent_id = ?")
        .params(agentId.value())
        .query(String.class)
        .single();
  }

  private int outstandingEffects(AgentId agentId) {
    return engine
        .jdbc()
        .sql("SELECT count(*) FROM nessy_agent_effect WHERE agent_id = ?")
        .params(agentId.value())
        .query(Integer.class)
        .single();
  }

  /** Answers perfectly well, and counts how often it was asked to. */
  static class CountingModel implements InferenceProvider {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
      calls.incrementAndGet();
      return new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("hello"));
    }

    int calls() {
      return calls.get();
    }
  }

  /** Fails in a way the handler does not catch, so it reaches the retry decision. */
  static class AlwaysBroken implements InferenceProvider {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
      calls.incrementAndGet();
      throw new IllegalStateException("nope");
    }

    int calls() {
      return calls.get();
    }
  }
}
