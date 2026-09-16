package org.jwcarman.nessy.engine.inference;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.inference.Usage;

/**
 * A provider observed the way the OpenTelemetry GenAI semantic conventions describe a model call:
 * one span per inference, named {@code chat <model>}, timed in the {@code
 * gen_ai.client.operation.duration} histogram, tagged with the provider, the model, how the model
 * finished and, when it did not, why.
 *
 * <p>Here rather than in the Boot starter because every model call should look the same on a
 * dashboard whoever made it: the engine's turns, and the summarisers working in the background.
 *
 * <p>Token counts go on the span as {@code gen_ai.usage.input_tokens} and {@code
 * gen_ai.usage.output_tokens}, and into the observation's context as a {@link Usage} for a handler
 * that has a meter registry to record semconv's {@code gen_ai.client.token.usage} histogram -- the
 * Boot starter registers one.
 */
public final class ObservedInference {

  /** Semconv's histogram of how long a GenAI operation took. */
  public static final String DURATION = "gen_ai.client.operation.duration";

  private static final String OPERATION_NAME = "gen_ai.operation.name";
  private static final String FINISH_REASONS = "gen_ai.response.finish_reasons";
  private static final String ERROR_TYPE = "error.type";

  private ObservedInference() {}

  /**
   * @param providerName the semconv {@code gen_ai.provider.name} for this vendor -- {@code
   *     "openai"}, {@code "anthropic"}, {@code "gcp.gemini"}, {@code "aws.bedrock"}. Passed in
   *     because the application that built the provider is the one thing that knows; each adapter
   *     publishes the right value as its own {@code PROVIDER_NAME}.
   */
  public static InferenceProvider provider(
      InferenceProvider delegate, String providerName, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(providerName, "providerName must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    return (request, narrator) -> {
      // Asked per call, not once: a registry is no-op until a handler is registered, which may
      // happen after this wrapper is built.
      if (observations.isNoop()) {
        return delegate.infer(request, narrator);
      }
      String model = request.options().modelName();
      Observation observation =
          Observation.createNotStarted(DURATION, observations)
              // Semconv's span name is "{operation} {model}", which the metric name cannot also
              // be -- so the contextual name carries it and the meter keeps the histogram's name.
              .contextualName("chat " + model)
              .lowCardinalityKeyValue(OPERATION_NAME, "chat")
              .lowCardinalityKeyValue("gen_ai.provider.name", providerName)
              .lowCardinalityKeyValue("gen_ai.request.model", model)
              // Set at START, not on outcome. Micrometer compares an observation's key set
              // against others recorded under the same name, so a chat that only sometimes
              // carried a finish reason would be a different shape from one that did.
              .lowCardinalityKeyValue(FINISH_REASONS, "none")
              .lowCardinalityKeyValue(ERROR_TYPE, "none")
              .start();
      try {
        InferenceResult result = delegate.infer(request, narrator);
        observation.lowCardinalityKeyValue(FINISH_REASONS, finishReasonOf(result));
        if (result.usage().known()) {
          // Semconv's attributes on the span, and the count itself in the context for a handler
          // with a meter registry to put in gen_ai.client.token.usage; a count is never a tag on
          // a metric, or every distinct number would be a time series.
          observation.highCardinalityKeyValue(
              "gen_ai.usage.input_tokens", Long.toString(result.usage().inputTokens()));
          observation.highCardinalityKeyValue(
              "gen_ai.usage.output_tokens", Long.toString(result.usage().outputTokens()));
          observation.getContext().put(Usage.class, result.usage());
        }
        // A provider that answers with a Fault did not throw, and the span must still say so:
        // the failure is a value, and a value nothing recorded would be a call that looks
        // successful in every dashboard.
        if (result instanceof InferenceResult.Fault(Failure failure, var _)) {
          observation.lowCardinalityKeyValue(ERROR_TYPE, failure.getClass().getSimpleName());
          // The adapter's own account of what went wrong, which is the one thing worth reading
          // on the span. High cardinality, so it reaches the trace and stays out of the metric.
          observation.highCardinalityKeyValue("error.message", failure.reason());
        }
        return result;
      } catch (RuntimeException e) {
        observation.lowCardinalityKeyValue(ERROR_TYPE, e.getClass().getSimpleName());
        observation.error(e);
        throw e;
      } finally {
        observation.stop();
      }
    };
  }

  /**
   * What the model did, in semconv's vocabulary. Exhaustive, so a new kind of result has to be
   * given a name here rather than silently reported as whatever the last arm happened to be.
   */
  static String finishReasonOf(InferenceResult result) {
    return switch (result) {
      case InferenceResult.Answer _ -> "stop";
      case InferenceResult.Actions _ -> "tool_calls";
      case InferenceResult.Refusal _ -> "content_filter";
      case InferenceResult.Fault _ -> "error";
    };
  }
}
