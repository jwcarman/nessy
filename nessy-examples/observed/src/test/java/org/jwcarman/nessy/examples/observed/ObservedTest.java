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
package org.jwcarman.nessy.examples.observed;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Observed is its own test, exactly as {@code HelloTest} is: the offline default build runs this
 * with {@code --scripted} and no collector reachable, so this stays proof that the export paths
 * never turn a missing collector into a failed turn — not just a claim made once when this module
 * was written.
 */
class ObservedTest {

  private static final String SERVICE_NAME = "nessy-example-observed";

  @Test
  void the_scripted_turn_settles_on_the_advertised_answer_with_no_collector_reachable() {
    String line = Observed.run(List.of("--scripted"));

    assertThat(line).isEqualTo("The answer is 4. (COMPLETE)");
  }

  /**
   * {@code schema_url} on the instrumentation scope (in-the-loop amendment §2). GenAI semconv is
   * still Development status — {@code gen_ai.system} became {@code gen_ai.provider.name} inside a
   * year — so a Collector's schema processor needs to know which revision these attributes were
   * written against before it can translate them forward.
   *
   * <p>It is set on the OpenTelemetry {@code Tracer} and nowhere else, which is why it lives in an
   * example rather than in {@code nessy-agent}: Micrometer's {@code ObservationRegistry} API has no
   * notion of a schema URL, {@code OtelTracer} wraps whatever tracer it is handed, and the harness
   * never sees an OpenTelemetry type at all.
   */
  @Nested
  class TheInstrumentationScope {

    @Test
    void every_span_this_example_exports_declares_the_semconv_schema_url() {
      List<ReadableSpan> ended = new ArrayList<>();
      SdkTracerProvider provider =
          SdkTracerProvider.builder().addSpanProcessor(new Capturing(ended)).build();
      OpenTelemetry openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();

      Observed.tracer(openTelemetry).spanBuilder("probe").startSpan().end();

      assertThat(ended)
          .singleElement()
          .satisfies(
              span ->
                  assertThat(span.getInstrumentationScopeInfo().getSchemaUrl())
                      .isEqualTo("https://opentelemetry.io/schemas/1.44.0"));
    }
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

  @Nested
  class TheExportedServiceName {

    /**
     * The README tells a reader to find this run in Tempo and Loki by service name, so the name has
     * to be on the SDK's {@code Resource} — naming the tracer's instrumentation scope leaves every
     * signal labelled {@code unknown_service:java} and those instructions false.
     */
    @Test
    void the_resource_every_signal_carries_names_the_service() {
      assertThat(Observed.serviceResource().getAttribute(AttributeKey.stringKey("service.name")))
          .isEqualTo(SERVICE_NAME);
    }

    /**
     * And it is on the providers themselves, not merely available to them. {@code
     * SdkTracerProvider} exposes no resource accessor, so its own {@code toString} — which prints
     * the resource it was built with — is the only public window onto what it will export.
     */
    @Test
    void the_tracer_and_logger_providers_are_built_with_that_resource() {
      Observed.Telemetry telemetry = Observed.telemetry();
      try {
        assertThat(telemetry.tracerProvider().toString()).contains(SERVICE_NAME);
        assertThat(telemetry.loggerProvider().toString()).contains(SERVICE_NAME);
      } finally {
        telemetry.tracerProvider().close();
        telemetry.loggerProvider().close();
        telemetry.meterRegistry().close();
      }
    }

    /** The meter registry builds its own resource, so the name has to be set there separately. */
    @Test
    void the_metrics_exporter_carries_the_same_service_name() {
      assertThat(Observed.meterConfig().resourceAttributes())
          .containsEntry("service.name", SERVICE_NAME);
    }
  }

  /**
   * Soak finding F4 (2026-08-26): this test class runs the REAL export paths — that is its subject
   * — so on a box with a collector listening it was publishing spans under {@code
   * service.name=nessy-example-observed} on every build, indistinguishable from a real run. The
   * build points {@code OTEL_EXPORTER_OTLP_ENDPOINT} at a closed port on loopback (see this
   * module's surefire configuration). This is the assertion that says so out loud, so a lost
   * surefire block is a red build rather than a quietly polluted Tempo.
   */
  @Nested
  class TheTestsPublishNowhere {

    @Test
    void the_exporters_are_not_pointed_at_a_collectors_conventional_port() {
      String url = Observed.meterConfig().url();

      assertThat(url).doesNotContain(":4318").doesNotContain(":4317");
    }
  }

  @Nested
  class TheTokenUsageHandler {

    private static final String USAGE = "gen_ai.client.token.usage";

    private Observation.Context chatContext() {
      Observation.Context context = new Observation.Context();
      context.setName("chat");
      context.addLowCardinalityKeyValue(KeyValue.of("error.type", "none"));
      context.addLowCardinalityKeyValue(KeyValue.of("gen_ai.provider.name", "scripted"));
      context.addLowCardinalityKeyValue(KeyValue.of("gen_ai.request.model", "scripted"));
      return context;
    }

    @Test
    void a_chat_that_reported_usage_records_both_token_types() {
      var meters = new SimpleMeterRegistry();
      var handler = new TokenUsageHandler(meters);
      Observation.Context context = chatContext();
      context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.usage.input_tokens", "1234"));
      context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.usage.output_tokens", "56"));

      handler.onStop(context);

      assertThat(meters.get(USAGE).tag("gen_ai.token.type", "input").summary().totalAmount())
          .isEqualTo(1234.0);
      assertThat(meters.get(USAGE).tag("gen_ai.token.type", "output").summary().totalAmount())
          .isEqualTo(56.0);
      assertThat(meters.get(USAGE).tag("gen_ai.provider.name", "scripted").summaries())
          .isNotEmpty();
    }

    /**
     * The case that named fix round 1's containment rule, here on the application's own side of the
     * seam: a chat that failed before the model reported usage carries no {@code gen_ai.usage.*} at
     * all, and this handler records nothing rather than throwing on the turn already having a bad
     * day.
     */
    @Test
    void a_chat_that_never_reported_usage_records_nothing_and_does_not_throw() {
      var meters = new SimpleMeterRegistry();
      var handler = new TokenUsageHandler(meters);
      Observation.Context context = new Observation.Context();
      context.setName("chat");
      context.addLowCardinalityKeyValue(KeyValue.of("error.type", "IllegalStateException"));

      handler.onStop(context);

      assertThat(meters.find(USAGE).summaries()).isEmpty();
    }
  }
}
