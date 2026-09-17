package org.jwcarman.nessy.engine.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import java.util.function.Function;
import org.jwcarman.nessy.api.AmbientSource;

/**
 * Background gathered for one call, in a span named for the source: {@code nessy.context ambient
 * plan}, {@code ... notebook}.
 *
 * <p>Named from the source's own kind, so a source that offers nothing this turn is as identifiable
 * as one that offers something -- which is the case worth seeing, since it is the one that cost
 * time for no reason.
 */
public final class ObservedAmbientSource {

  private ObservedAmbientSource() {}

  public static AmbientSource wrap(AmbientSource delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    return AmbientSource.of(
        source ->
            source
                .kind(delegate.kind())
                .offering(
                    agentId ->
                        observe(
                            observations,
                            "nessy.context.ambient",
                            "nessy.context ambient " + delegate.kind(),
                            whose(observations),
                            observation -> delegate.forAgent(agentId))));
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
