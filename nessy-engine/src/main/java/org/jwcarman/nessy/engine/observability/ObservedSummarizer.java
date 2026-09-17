package org.jwcarman.nessy.engine.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;

/**
 * A summary source read as semconv's {@code search_memory}: an in-process memory store asked what
 * the model should be reminded of, and how many records it gave back.
 *
 * <p>Where relevance is ranked by embedding, the embedding call happens inside this span, so what
 * recall costs is visible where it is spent.
 */
public final class ObservedSummarizer {

  private static final String OPERATION_NAME = "gen_ai.operation.name";

  private ObservedSummarizer() {}

  public static Summarizer wrap(Summarizer delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    return new Summarizer() {
      @Override
      public List<Summary> forAgent(AgentId agentId) {
        return searched(observations, () -> delegate.forAgent(agentId));
      }

      @Override
      public List<Summary> forAgent(AgentId agentId, Turn current) {
        return searched(observations, () -> delegate.forAgent(agentId, current));
      }

      @Override
      public Optional<TurnId> summarizedThrough(AgentId agentId) {
        // No span: one cheap query, asked to decide where the tail begins rather than to decide
        // what the model is shown.
        return delegate.summarizedThrough(agentId);
      }
    };
  }

  private static List<Summary> searched(
      ObservationRegistry observations, Supplier<List<Summary>> read) {
    return observe(
        observations,
        "nessy.context.memory",
        // Semconv's span name for a memory operation is the operation alone.
        "search_memory",
        whose(observations),
        observation -> {
          observation.lowCardinalityKeyValue(OPERATION_NAME, "search_memory");
          List<Summary> found = read.get();
          observation.highCardinalityKeyValue(
              "gen_ai.memory.record.count", String.valueOf(found.size()));
          return found;
        });
  }

  /**
   * Opens one span and runs the work in it. No guard: {@code createNotStarted} answers a no-op
   * registry with a no-op observation, which takes every tag and records nothing -- and a guard at
   * wiring time would be wrong anyway, since a registry with no handlers YET looks no-op.
   */
  private static <T> T observe(
      ObservationRegistry observations,
      String name,
      String spanName,
      Identity whose,
      Function<Observation, T> work) {
    Observation observation =
        Observation.createNotStarted(name, observations).contextualName(spanName);
    if (whose != null) {
      whose.on(observation, null);
    }
    return observation.observe(() -> work.apply(observation));
  }

  private static Identity whose(ObservationRegistry observations) {
    return Identity.current(observations);
  }
}
