package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.engine.EngineUnderTest;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * The permit count bounds work in flight, and the batch is sized from it.
 *
 * <p>This builds its own harness rather than using the application's, because the number under test
 * is the thing being varied -- and it is a per-agent-type setting, so an agent type of this test's
 * own is the honest way to set it.
 */
class InFlightLimitTest {

  private static final int LIMIT = 2;
  private static final int AGENTS = 6;
  private static final AgentType LIMITED = new AgentType("limited");

  /** The whole record, flattened -- what was stored, not what would be sent. */
  private static List<HistoryEntry> story(
      EngineUnderTest engine, AgentType agentType, AgentId agentId) {
    return engine.history().entriesFrom(agentType, agentId, 0);
  }

  @Test
  void neverMoreThanTheLimitAreInFlightAtOnce() {
    Census census = new Census();
    try (EngineUnderTest engine = new EngineUnderTest(census)) {
      Harness<String> harness =
          engine
              .harnesses()
              .create(
                  String.class,
                  config ->
                      config
                          .agentType(LIMITED)
                          .systemPrompt("You are a test assistant.")
                          .inference(
                              in ->
                                  in.model("a-model")
                                      .retryPolicy(new RetryPolicy.Never())
                                      .timeout(Duration.ofMinutes(5)))
                          .effects(e -> e.maxInFlight(LIMIT).pollInterval(Duration.ofMillis(100))));

      List<AgentId> agents =
          Stream.generate(() -> new AgentId(UUID.randomUUID())).limit(AGENTS).toList();
      agents.forEach(agentId -> harness.observe(agentId, "hello"));

      agents.forEach(
          agentId ->
              await()
                  .atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(story(engine, LIMITED, agentId)).hasSize(2)));

      assertThat(census.peak())
          .as("six agents, two permits: the other four wait rather than pile on")
          .isLessThanOrEqualTo(LIMIT);
      assertThat(census.peak())
          .as("if nothing ever overlapped the limit would not be what proved it")
          .isGreaterThan(1);
    }
  }

  /** Counts how many calls are in the model at the same moment, and remembers the worst. */
  static class Census implements InferenceProvider {

    private final AtomicInteger current = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();

    @Override
    public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
      peak.accumulateAndGet(current.incrementAndGet(), Math::max);
      try {
        Thread.sleep(250);
        return new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("counted"));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      } finally {
        current.decrementAndGet();
      }
    }

    int peak() {
      return peak.get();
    }
  }
}
