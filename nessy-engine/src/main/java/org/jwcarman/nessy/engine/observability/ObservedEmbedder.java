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
import java.util.function.Supplier;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.Embedding;

/**
 * An embedder whose every call is an observation named the way OpenTelemetry's GenAI semantic
 * conventions name one: a span called {@code embeddings <model>}, timed by semconv's {@code
 * gen_ai.client.operation.duration}.
 *
 * <p>An embedding call sits on the path of a model call wherever memory is ranked by relevance, so
 * one that is slow is latency nothing else would name.
 */
public final class ObservedEmbedder implements Embedder, AutoCloseable {

  /** Semconv's histogram of how long a GenAI operation took, shared with model calls. */
  static final String DURATION = "gen_ai.client.operation.duration";

  private static final String OPERATION_NAME = "gen_ai.operation.name";
  private static final String ERROR_TYPE = "error.type";

  /**
   * Which half of retrieval this call was.
   *
   * <p>Nessy's own key. Semconv has no word for it, and the distinction is real: the two are
   * different calls to the model with different latency that anybody reading a trace wants apart.
   */
  private static final String INPUT_TYPE = "gen_ai.embeddings.input_type";

  private static final String DOCUMENT = "document";
  private static final String QUERY = "query";

  /** Whose call it is, read off the span this one opens under, so it reads the same as theirs. */
  private static final List<String> LOW_CARDINALITY_IDENTITY = List.of("gen_ai.agent.name");

  private static final List<String> HIGH_CARDINALITY_IDENTITY =
      List.of("gen_ai.conversation.id", "nessy.turn.id");

  private final Embedder delegate;
  private final ObservationRegistry observations;

  /** The embedder this wraps, observed once: wrapping an observed one gives it back. */
  public static Embedder wrap(Embedder delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    // Wrapping an observed one would make every call two spans; everything else is wrapped, with
    // a no-op registry costing a check per call and nothing else.
    return delegate instanceof ObservedEmbedder already
        ? already
        : new ObservedEmbedder(delegate, observations);
  }

  private ObservedEmbedder(Embedder delegate, ObservationRegistry observations) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
  }

  @Override
  public String providerName() {
    return delegate.providerName();
  }

  /**
   * Closes the embedder it wraps, when that one holds something to close. A container that manages
   * the observed embedder closes it, and the client inside would otherwise leak.
   */
  @Override
  public void close() throws Exception {
    if (delegate instanceof AutoCloseable closeable) {
      closeable.close();
    }
  }

  @Override
  public String model() {
    return delegate.model();
  }

  @Override
  public int dimension() {
    return delegate.dimension();
  }

  @Override
  public Embedding embedDocument(String text) {
    return observe(DOCUMENT, () -> delegate.embedDocument(text));
  }

  @Override
  public List<Embedding> embedDocuments(List<String> texts) {
    return observe(DOCUMENT, () -> delegate.embedDocuments(texts));
  }

  /**
   * Wrapped like the others, and delegated rather than derived.
   *
   * <p>Taking the default would embed the query as a document through this wrapper and never ask
   * the delegate which it was -- which is the whole distinction, lost in the one place that was
   * only supposed to be watching.
   */
  @Override
  public Embedding embedQuery(String query) {
    return observe(QUERY, () -> delegate.embedQuery(query));
  }

  private <T> T observe(String role, Supplier<T> call) {
    // Asked per call, not once: a registry is no-op until a handler is registered, which may happen
    // after this wrapper is built.
    if (observations.isNoop()) {
      return call.get();
    }
    String model = delegate.model();
    Observation observation =
        Observation.createNotStarted(DURATION, observations)
            .contextualName("embeddings " + model)
            .lowCardinalityKeyValue(OPERATION_NAME, "embeddings")
            .lowCardinalityKeyValue("gen_ai.provider.name", delegate.providerName())
            .lowCardinalityKeyValue("gen_ai.request.model", model)
            // The same tag keys a model call carries under this name, because a meter's keys are
            // fixed: an embedding has no finish reason, and says so rather than leaving it off.
            .lowCardinalityKeyValue("gen_ai.response.finish_reasons", "none")
            .lowCardinalityKeyValue(ERROR_TYPE, "none")
            // Two values, so a metric can be split by it. Worth splitting: a document is embedded
            // off the turn, where nobody is waiting, and a query is embedded while somebody is --
            // one number over both hides which of them is slow.
            .lowCardinalityKeyValue(INPUT_TYPE, role)
            .highCardinalityKeyValue(
                "gen_ai.embeddings.dimension.count", String.valueOf(delegate.dimension()));
    inheritIdentity(observation);
    observation.start();
    try (var _ = observation.openScope()) {
      return call.get();
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
}
