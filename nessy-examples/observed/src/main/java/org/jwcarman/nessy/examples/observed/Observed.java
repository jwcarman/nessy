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
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.TurnOutcome;
import org.jwcarman.nessy.agent.host.Nessy;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.model.discovery.ModelDiscovery;
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
    Model model = scripted ? scriptedModel() : ModelDiscovery.select().model();
    ModelSettings settings = new ModelSettings(1024, Set.of(), null);
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
  private record Telemetry(
      SdkTracerProvider tracerProvider,
      SdkLoggerProvider loggerProvider,
      OtlpMeterRegistry meterRegistry) {}

  private static Telemetry telemetry() {
    var spanExporter = OtlpGrpcSpanExporter.builder().setEndpoint(tracesEndpoint()).build();
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
            .build();

    var logExporter = OtlpGrpcLogRecordExporter.builder().setEndpoint(tracesEndpoint()).build();
    SdkLoggerProvider loggerProvider =
        SdkLoggerProvider.builder()
            .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build())
            .build();

    OtlpConfig meterConfig =
        new OtlpConfig() {
          @Override
          public String get(String key) {
            return null; // everything but url() rides OtlpConfig's own defaults
          }

          @Override
          public String url() {
            return metricsUrl();
          }
        };
    OtlpMeterRegistry meterRegistry = new OtlpMeterRegistry(meterConfig, Clock.SYSTEM);

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

    var otelTracer = openTelemetry.getTracer(SERVICE_NAME);
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

  private static String tracesEndpoint() {
    String base = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
    // The collector's gRPC trace listener sits on 4317, one port over from the HTTP metrics
    // listener the env var's own default (4318) names — swap it rather than dropping the caller's
    // host/scheme.
    return base != null ? base.replace(":4318", ":4317") : DEFAULT_TRACES_ENDPOINT;
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
