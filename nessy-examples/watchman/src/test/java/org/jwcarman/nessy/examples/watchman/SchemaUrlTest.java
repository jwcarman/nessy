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
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code schema_url} on the instrumentation scope (in-the-loop amendment §2, and the collector's
 * schema processor).
 *
 * <p>GenAI semconv is still Development status — {@code gen_ai.system} became {@code
 * gen_ai.provider.name} inside a year — so a collector needs to know which revision of the
 * conventions a span's attributes were written against before it can translate them forward. That
 * is the instrumentation scope's schema URL, and it is set exactly once, on the OpenTelemetry
 * {@code Tracer}: {@code openTelemetry.tracerBuilder(name).setSchemaUrl(url).build()}.
 *
 * <p><b>It cannot be set anywhere else, and specifically not in {@code nessy-agent}.</b>
 * Micrometer's {@code Observation}/{@code ObservationRegistry} API has no concept of a schema URL —
 * it has no concept of OpenTelemetry at all — and {@code micrometer-tracing-bridge-otel} simply
 * wraps whatever {@code Tracer} it is handed, so every span it creates inherits that tracer's
 * scope. Spring Boot's own {@code OpenTelemetryTracingAutoConfiguration} builds that tracer as
 * {@code openTelemetry.getTracer("org.springframework.boot", version)} — no schema URL — under
 * {@code @ConditionalOnMissingBean}, which is precisely the seam {@link Telemetry} takes over here.
 * The harness never sees an OpenTelemetry type, so application-side is not a compromise: it is the
 * only correct place.
 */
class SchemaUrlTest {

  @Test
  void the_tracer_this_application_supplies_stamps_the_semconv_schema_url_on_its_scope() {
    List<ReadableSpan> ended = new ArrayList<>();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(new Capturing(ended)).build();
    OpenTelemetry openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();

    new Telemetry().otelTracer(openTelemetry).spanBuilder("probe").startSpan().end();

    assertThat(ended)
        .singleElement()
        .satisfies(
            span ->
                assertThat(span.getInstrumentationScopeInfo().getSchemaUrl())
                    .isEqualTo(Telemetry.SEMCONV_SCHEMA_URL));
  }

  /** The declared version is the one the o11y spec's semconv audit was performed against. */
  @Test
  void the_declared_schema_url_names_the_semconv_version_the_roster_was_audited_against() {
    assertThat(Telemetry.SEMCONV_SCHEMA_URL).isEqualTo("https://opentelemetry.io/schemas/1.44.0");
  }

  /** A hand-written processor, because there is no mocking library here (design of record). */
  private record Capturing(List<ReadableSpan> ended) implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
      // nothing to record until the span ends
    }

    @Override
    public boolean isStartRequired() {
      return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
      ended.add(span);
    }

    @Override
    public boolean isEndRequired() {
      return true;
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
