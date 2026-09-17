package org.jwcarman.nessy.engine.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import java.util.function.Function;
import org.jwcarman.nessy.engine.inference.InferenceContextAssembler;

/**
 * Everything the model is shown, assembled in one {@code nessy.context} span, with each read
 * beneath it.
 *
 * <p>What this measures is the part of a model call that is not the model: before today it was
 * indistinguishable from the provider's own latency.
 */
public final class ObservedInferenceContextAssembler {

  public static final String CONTEXT = "nessy.context";

  private ObservedInferenceContextAssembler() {}

  public static InferenceContextAssembler wrap(
      InferenceContextAssembler delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    return invocation ->
        observe(
            observations,
            CONTEXT,
            CONTEXT,
            new Identity(invocation.agentType(), invocation.agentId()),
            observation -> delegate.assemble(invocation));
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
}
