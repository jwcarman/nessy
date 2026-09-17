package org.jwcarman.nessy.spring.boot;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jwcarman.nessy.engine.trace.TraceCarrier;

/**
 * Writes the trace in force straight into headers with the application's own propagator.
 *
 * <p>What the engine cannot do on its own: it speaks observations, and an observation only has
 * headers written for it by starting one. Boot already holds the {@link Tracer} and {@link
 * Propagator} that do the writing, so an effect row gets its parent with no span left behind.
 */
final class PropagatingTraceCarrier implements TraceCarrier {

  private final Tracer tracer;
  private final Propagator propagator;

  PropagatingTraceCarrier(Tracer tracer, Propagator propagator) {
    this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
    this.propagator = Objects.requireNonNull(propagator, "propagator must not be null");
  }

  @Override
  public Map<String, String> capture() {
    TraceContext current = tracer.currentTraceContext().context();
    if (current == null) {
      return Map.of();
    }
    Map<String, String> headers = new LinkedHashMap<>();
    propagator.inject(current, headers, Map::put);
    return headers;
  }
}
