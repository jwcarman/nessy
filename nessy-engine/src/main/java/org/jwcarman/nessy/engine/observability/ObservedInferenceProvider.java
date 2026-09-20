/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.engine.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * A provider observed the way the OpenTelemetry GenAI semantic conventions describe a model call:
 * one span per inference, named {@code chat <model>}, timed in the {@code
 * gen_ai.client.operation.duration} histogram, tagged with the provider, the model, how the model
 * finished and, when it did not, why.
 *
 * <p>Wrapped where a provider is handed over -- by the engine, by a summariser, by the Boot
 * starter's auto-configuration -- so every model call looks the same on a dashboard whoever made
 * it: the engine's turns, and the summarisers working in the background.
 *
 * <p>Token counts go on the span as {@code gen_ai.usage.input_tokens} and {@code
 * gen_ai.usage.output_tokens}, and into the observation's context as a {@link Usage} for a handler
 * that has a meter registry to record semconv's {@code gen_ai.client.token.usage} histogram -- the
 * Boot starter registers one.
 */
public final class ObservedInferenceProvider implements InferenceProvider {

  /** Semconv's histogram of how long a GenAI operation took. */
  public static final String DURATION = "gen_ai.client.operation.duration";

  private static final String OPERATION_NAME = "gen_ai.operation.name";
  private static final String FINISH_REASONS = "gen_ai.response.finish_reasons";
  private static final String ERROR_TYPE = "error.type";

  /** Whose call it is, read off the span this one opens under, so it reads the same as theirs. */
  private static final List<String> LOW_CARDINALITY_IDENTITY = List.of("gen_ai.agent.name");

  private static final List<String> HIGH_CARDINALITY_IDENTITY = List.of("gen_ai.conversation.id");

  private final InferenceProvider delegate;
  private final ObservationRegistry observations;

  /** The provider this wraps, observed once: wrapping an observed one gives it back. */
  public static InferenceProvider wrap(
      InferenceProvider delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    // Wrapping an observed one would make every call two spans; everything else is wrapped, with
    // a no-op registry costing a check per call and nothing else.
    return delegate instanceof ObservedInferenceProvider already
        ? already
        : new ObservedInferenceProvider(delegate, observations);
  }

  private ObservedInferenceProvider(InferenceProvider delegate, ObservationRegistry observations) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
  }

  @Override
  public String providerName() {
    return delegate.providerName();
  }

  @Override
  public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
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
            .lowCardinalityKeyValue("gen_ai.provider.name", delegate.providerName())
            .lowCardinalityKeyValue("gen_ai.request.model", model)
            // Set at START, not on outcome. Micrometer compares an observation's key set
            // against others recorded under the same name, so a chat that only sometimes
            // carried a finish reason would be a different shape from one that did.
            .lowCardinalityKeyValue(FINISH_REASONS, "none")
            .lowCardinalityKeyValue(ERROR_TYPE, "none");
    // Whose call: read off the span this one opens under -- the effect, or the summary -- since
    // the provider is kept from knowing. The turn is the one the request is answering.
    inheritIdentity(observation);
    TurnId turn = openTurn(request);
    if (turn != null) {
      observation.highCardinalityKeyValue("nessy.turn.id", Long.toString(turn.value()));
    }
    observation.start();
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
      if (result instanceof InferenceResult.Fault(Failure failure, _)) {
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
  }

  private void inheritIdentity(Observation observation) {
    Observation parent = observations.getCurrentObservation();
    if (parent == null) {
      return;
    }
    Observation.ContextView context = parent.getContextView();
    for (String key : LOW_CARDINALITY_IDENTITY) {
      KeyValue value = context.getLowCardinalityKeyValue(key);
      if (value != null) {
        observation.lowCardinalityKeyValue(value);
      }
    }
    for (String key : HIGH_CARDINALITY_IDENTITY) {
      KeyValue value = context.getHighCardinalityKeyValue(key);
      if (value != null) {
        observation.highCardinalityKeyValue(value);
      }
    }
  }

  /** The last turn in the context is the one being answered; a first call has none. */
  private static TurnId openTurn(InferenceRequest request) {
    var turns = request.context().turns();
    return turns.isEmpty() ? null : turns.getLast().id();
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
