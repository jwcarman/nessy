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
