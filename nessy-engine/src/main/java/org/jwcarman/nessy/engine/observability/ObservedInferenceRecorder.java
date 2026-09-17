package org.jwcarman.nessy.engine.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.inference.InferenceRecorder;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/**
 * Writing down what the model was shown, as {@code nessy.record}: it happens before the provider is
 * asked, so it is time a person waits for.
 *
 * <p>Only the opening write is timed. The outcome is written after the call has returned, when
 * nobody is waiting on it.
 */
public final class ObservedInferenceRecorder {

  private ObservedInferenceRecorder() {}

  public static InferenceRecorder wrap(
      InferenceRecorder delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    return new InferenceRecorder() {
      @Override
      public UUID begin(AgentType agentType, AgentId agentId, InferenceRequest request) {
        return observe(
            observations,
            "nessy.record",
            "nessy.record",
            new Identity(agentType, agentId),
            observation -> delegate.begin(agentType, agentId, request));
      }

      @Override
      public void end(UUID id, InferenceResult result) {
        delegate.end(id, result);
      }

      @Override
      public void failed(UUID id) {
        delegate.failed(id);
      }
    };
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
