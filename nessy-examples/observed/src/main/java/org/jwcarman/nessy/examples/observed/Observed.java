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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.TurnOutcome;
import org.jwcarman.nessy.agent.host.Nessy;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.model.discovery.ModelDiscovery;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.testing.ScriptedModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hello's turn, exported: the same calculator-tool question, run against a real {@link
 * ObservationRegistry} wired to a collector over OTLP (agentic-o11y spec §4). {@code --scripted}
 * swaps in {@link ScriptedModel}, exactly as {@code Hello} does — the exporters run whether or not
 * a collector is listening, and neither export path ever throws through this turn (see {@link
 * #shutdownQuietly}).
 */
public final class Observed {

  private static final Logger log = LoggerFactory.getLogger(Observed.class);

  private static final String SYSTEM_PROMPT = "You are a helpful assistant with a calculator tool.";
  private static final String QUESTION = "What is 2+2? Use the calculator tool.";
  private static final String SERVICE_NAME = "nessy-example-observed";

  /**
   * The resource attribute every OTLP backend groups by. It is a property of the SDK's {@code
   * Resource}, NOT of the tracer's instrumentation-scope name — passing {@link #SERVICE_NAME} to
   * {@code getTracer(...)} names the scope alone, and leaves every exported span, log and metric
   * labelled {@code unknown_service:java}, which is exactly what the README's "search by service
   * name" instructions would then fail to find.
   */
  private static final AttributeKey<String> SERVICE_NAME_KEY =
      AttributeKey.stringKey("service.name");

  /**
   * OTLP/gRPC's own default ({@code OtlpGrpcSpanExporter}'s builder default): port 4317. {@code
   * OTEL_EXPORTER_OTLP_ENDPOINT} names an OTLP/HTTP base (metrics' own default port is 4318,
   * below); traces here ride gRPC on the collector's other listener, so a caller who sets the env
   * var to point at a non-default host still gets that host on the gRPC port.
   */
  private static final String DEFAULT_TRACES_ENDPOINT = "http://localhost:4317";

  /**
   * {@code OtlpConfig}'s own default (Micrometer's {@code micrometer-registry-otlp}): {@code
   * http://localhost:4318/v1/metrics}, OTLP/HTTP protobuf.
   */
  private static final String DEFAULT_METRICS_URL = "http://localhost:4318/v1/metrics";

  /**
   * The semantic-conventions revision this harness's {@code gen_ai.*} attributes were written and
   * audited against — the o11y spec's §8b audit references semantic-conventions v1.44.0. A schema
   * URL is always {@code https://opentelemetry.io/schemas/<version>}.
   */
  static final String SEMCONV_SCHEMA_URL = "https://opentelemetry.io/schemas/1.44.0";

  private Observed() {}

  public static void main(String[] args) {
    System.out.println(run(Arrays.asList(args)));
  }

  /**
   * The whole example, factored out so {@code ObservedTest} can assert on exactly what runs.
   *
   * <p>{@code Nessy.cli()} has no {@code .observationRegistry(...)} seam of its own — the console
   * builder mirrors {@code HarnessConfig}'s surface only for the pieces a terminal session needs
   * (see the harness guide). The seam this example exists to show lives on {@code HarnessConfig}
   * itself, so this reaches it directly through {@code Nessy.harness(...)} and drives one turn with
   * {@code Agent#ask}, the same pattern {@code ask}'s own javadoc describes.
   */
  static String run(Iterable<String> args) {
    boolean scripted = contains(args, "--scripted");
    if (scripted) {
      return run(scriptedModel());
    }
    // try-with-resources over the SELECTION, not the model: discovery builds a vendor gateway —
    // an SDK client, its connection pool, its threads — and closing the selection is the only
    // handle an application has on any of it (ModelProvider is AutoCloseable, ruled 2026-08-26).
    // An example whose whole subject is what a long-running process should do had better do it.
    try (ModelDiscovery.Selection selection = ModelDiscovery.select()) {
      return run(selection.model());
    }
  }

  private static String run(Model model) {
    // PROMPT_CACHING requested, not assumed: a provider that cannot do it says so, and the two
    // cache-token attributes on the chat span (gen_ai.usage.cache_read/cache_write.input_tokens)
    // are how you find out whether it did.
    ModelSettings settings = new ModelSettings(1024, Set.of(Capability.PROMPT_CACHING), null);
    Tool<Calculate> calculator =
        Tool.of(
            Calculate.class,
            t ->
                t.description("Adds two integers.")
                    .executes(calc -> String.valueOf(calc.left() + calc.right())));

    Telemetry telemetry = telemetry();
    ObservationRegistry registry = observationRegistry(telemetry);

    Harness<String> harness =
        Nessy.harness(
            h ->
                h.model(model)
                    .systemPrompt(SYSTEM_PROMPT)
                    .settings(settings)
                    .tools(calculator)
                    .observationRegistry(registry));
    try {
      TurnOutcome outcome = harness.bind(AgentId.of("observed")).ask(QUESTION);
      String reply =
          switch (outcome) {
            case TurnOutcome.Replied(String text) -> text;
            case TurnOutcome.Failed(String reason) -> "FAILED: " + reason;
            case TurnOutcome.Parked _ -> "PARKED";
          };
      return reply + " (COMPLETE)";
    } finally {
      harness.shutdown();
      shutdownQuietly(telemetry);
    }
  }

  /**
   * The three export paths, held together so {@link #run} has one thing to shut down: (a) traces
   * over OTLP/gRPC, logs riding the same gRPC collector (through the logback appender installed in
   * {@link #observationRegistry}), and (b) metrics over OTLP/HTTP — different wire protocols
   * because that is what each artifact's own exporter speaks by default (agentic-o11y spec §4).
   */
  record Telemetry(
      SdkTracerProvider tracerProvider,
      SdkLoggerProvider loggerProvider,
      OtlpMeterRegistry meterRegistry) {}

  /**
   * What every exported signal is labelled with. Merged onto {@link Resource#getDefault()} so the
   * SDK's own telemetry-sdk attributes survive alongside this one.
   */
  static Resource serviceResource() {
    return Resource.getDefault()
        .merge(Resource.create(Attributes.of(SERVICE_NAME_KEY, SERVICE_NAME)));
  }

  /** The metrics exporter's config, with the same {@code service.name} the other two carry. */
  static OtlpConfig meterConfig() {
    return new OtlpConfig() {
      @Override
      public String get(String key) {
        return null; // everything overridden below rides OtlpConfig's own defaults
      }

      @Override
      public String url() {
        return metricsUrl();
      }

      @Override
      public Map<String, String> resourceAttributes() {
        // The meter registry builds its own resource rather than sharing the SDK's, so the name
        // has to be set here too or the metrics arrive under a different service than the spans.
        return Map.of(SERVICE_NAME_KEY.getKey(), SERVICE_NAME);
      }
    };
  }

  static Telemetry telemetry() {
    Resource resource = serviceResource();
    String traces = tracesEndpoint();
    log.info(
        "exporting traces and logs to {} (OTLP/gRPC) and metrics to {} (OTLP/HTTP) as service.name={}",
        traces,
        metricsUrl(),
        SERVICE_NAME);

    var spanExporter = OtlpGrpcSpanExporter.builder().setEndpoint(traces).build();
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
            .build();

    var logExporter = OtlpGrpcLogRecordExporter.builder().setEndpoint(traces).build();
    SdkLoggerProvider loggerProvider =
        SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build())
            .build();

    OtlpMeterRegistry meterRegistry = new OtlpMeterRegistry(meterConfig(), Clock.SYSTEM);

    return new Telemetry(tracerProvider, loggerProvider, meterRegistry);
  }

  /**
   * The registry the harness records into: the tracing bridge (a), {@code
   * DefaultMeterObservationHandler} over the OTLP meter registry (b), and the ten-line token-usage
   * handler (c) that turns {@code chat}'s {@code gen_ai.usage.*} key-values into the semconv {@code
   * gen_ai.client.token.usage} metric (spec §1.2 — an {@code ObservationRegistry} cannot record a
   * value histogram itself, so this is the application's to do). Installing the logback appender
   * here, once the SDK exists, is what makes this class's own log lines ride the same trace/log
   * pipeline as the spans — a no-op, per the appender's own contract, until {@code install} runs.
   */
  private static ObservationRegistry observationRegistry(Telemetry telemetry) {
    OpenTelemetrySdk openTelemetry =
        OpenTelemetrySdk.builder()
            .setTracerProvider(telemetry.tracerProvider())
            .setLoggerProvider(telemetry.loggerProvider())
            .build();
    OpenTelemetryAppender.install(openTelemetry);

    var otelTracer = tracer(openTelemetry);
    var currentTraceContext = new OtelCurrentTraceContext();
    var tracer = new OtelTracer(otelTracer, currentTraceContext, event -> {});

    ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(new DefaultTracingObservationHandler(tracer))
        .observationHandler(new DefaultMeterObservationHandler(telemetry.meterRegistry()))
        .observationHandler(new TokenUsageHandler(telemetry.meterRegistry()));
    return registry;
  }

  /**
   * The tracer Micrometer's bridge turns every harness observation into a span through, carrying
   * the instrumentation scope's {@code schema_url} — so a Collector's schema processor can
   * translate our attributes forward if semconv renames one, which it does: {@code gen_ai.system}
   * became {@code gen_ai.provider.name} inside a year, and every GenAI convention is still
   * Development status.
   *
   * <p>This is the only place it CAN be set. Micrometer's {@code Observation}/{@code
   * ObservationRegistry} API has no notion of a schema URL — it has no notion of OpenTelemetry at
   * all, which is the point of the one-seam ruling — and {@code OtelTracer} wraps whatever {@code
   * Tracer} it is handed, so every span inherits that tracer's scope. {@code nessy-agent} depends
   * on {@code micrometer-observation} alone and never sees an OpenTelemetry type, so it has nothing
   * to stamp: application-side is not a compromise here, it is the only correct home.
   */
  static Tracer tracer(OpenTelemetry openTelemetry) {
    return openTelemetry.tracerBuilder(SERVICE_NAME).setSchemaUrl(SEMCONV_SCHEMA_URL).build();
  }

  /**
   * Where spans and logs go. {@code OTEL_EXPORTER_OTLP_ENDPOINT} names an OTLP/HTTP base, whose
   * conventional port is 4318; the collector's gRPC listener is one port over, on 4317. So a base
   * that explicitly names {@code :4318} is swapped — the caller clearly meant "this collector" and
   * not "this port for everything".
   *
   * <p>A base naming any OTHER port, or none at all, is used verbatim (fix round 2): silently
   * rewriting a port the caller did not name would be a guess, and a base with no port is already
   * whatever its scheme's default is. {@link #telemetry()} logs the endpoints it settled on either
   * way, so a misdirected export is visible in the first line of output rather than only as missing
   * data in Tempo.
   */
  private static String tracesEndpoint() {
    String base = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
    if (base == null) {
      return DEFAULT_TRACES_ENDPOINT;
    }
    return base.contains(":4318") ? base.replace(":4318", ":4317") : base;
  }

  private static String metricsUrl() {
    String base = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
    return base != null ? base + "/v1/metrics" : DEFAULT_METRICS_URL;
  }

  /**
   * Both exporters are documented never to throw through the caller (the trace processor logs a
   * failed export and moves on; Micrometer's push registry catches and logs its own publish
   * failures) — this is one more layer of the same rule the harness itself follows (agentic-o11y
   * fix round 1): telemetry describes the work, it never participates in it. Run with no collector
   * listening — {@code --scripted}, no Docker — to see the WARN this method's own try/catch would
   * otherwise be the only thing standing between a missing collector and a crashed demo.
   */
  private static void shutdownQuietly(Telemetry telemetry) {
    try {
      telemetry.tracerProvider().forceFlush().join(5, TimeUnit.SECONDS);
      telemetry.tracerProvider().shutdown().join(5, TimeUnit.SECONDS);
    } catch (RuntimeException e) {
      log.warn("flushing the trace exporter failed; no collector was reachable", e);
    }
    try {
      telemetry.loggerProvider().forceFlush().join(5, TimeUnit.SECONDS);
      telemetry.loggerProvider().shutdown().join(5, TimeUnit.SECONDS);
    } catch (RuntimeException e) {
      log.warn("flushing the log exporter failed; no collector was reachable", e);
    }
    try {
      telemetry.meterRegistry().close();
    } catch (RuntimeException e) {
      log.warn("flushing the metrics exporter failed; no collector was reachable", e);
    }
  }

  private static ScriptedModel scriptedModel() {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.put("left", 2);
    arguments.put("right", 2);
    return ScriptedModel.script(
        s ->
            s.toolUse("c1", "calculate", arguments)
                .endWithToolUse()
                .text("The answer is 4.")
                .endTurn());
  }

  private static boolean contains(Iterable<String> args, String flag) {
    for (String arg : args) {
      if (flag.equals(arg)) {
        return true;
      }
    }
    return false;
  }
}
