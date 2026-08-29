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
package org.jwcarman.nessy.engine;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;

/**
 * Builds {@link Traces} over an OpenTelemetry SDK for tests, exactly as Boot's tracing
 * autoconfiguration does in the application.
 *
 * <p>The tests still assert against the OTel SDK's {@code InMemorySpanExporter}, and that is the
 * point rather than an inconsistency: Micrometer is the API the port codes against, OTel is the
 * implementation underneath, and the spans that come out the bottom are what a backend will
 * actually receive. Asserting on the bridge's own types would test the bridge; asserting on
 * exported {@code SpanData} tests what Tempo sees.
 */
public final class MicrometerTracing {

  private MicrometerTracing() {}

  public static Traces over(OpenTelemetrySdk sdk) {
    io.opentelemetry.api.trace.Tracer otelTracer = sdk.getTracer("watchman-pekko");
    return new Traces(
        new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> {}),
        new OtelPropagator(sdk.getPropagators(), otelTracer));
  }

  /** For tests that exercise the actors but assert nothing about traces. */
  public static Traces noop() {
    return new Traces(Tracer.NOOP, Propagator.NOOP);
  }
}
