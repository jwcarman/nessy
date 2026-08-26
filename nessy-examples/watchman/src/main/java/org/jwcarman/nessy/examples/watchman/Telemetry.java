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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The three things the application must contribute to its own observability (spec §2.3).
 *
 * <p>Almost everything is Boot's, once {@code spring-boot-starter-opentelemetry} is on the
 * classpath: the actuator owns the {@code ObservationRegistry}, Micrometer's OTel bridge turns
 * observations into spans, and the OTLP exporters ship traces, metrics and logs to the LGTM box,
 * all steered from {@code management.otlp.*} and {@code management.tracing.*}. The starter hands
 * Boot's registry to the harness's seam, so every {@code invoke_agent}, {@code chat} and {@code
 * execute_tool} span is already there.
 *
 * <p><b>One.</b> {@code gen_ai.client.token.usage} is not: an {@code ObservationRegistry} times
 * observations and cannot record a value histogram, so the vendor's token counts ride the chat
 * observation as key-values and something has to turn them into a metric. Per the o11y spec's
 * ruling that is an application's job, not {@code nessy-agent}'s — hence {@link TokenUsageHandler},
 * registered here on Boot's own registry rather than on one built by hand.
 *
 * <p><b>Two.</b> The OTel logback appender has to be handed the {@link OpenTelemetry} instance
 * before it will ship anything, and nothing in Boot does that for you — {@code logback-spring.xml}
 * declaring the appender is necessary and not sufficient. Until {@link
 * OpenTelemetryAppender#install} is called the appender silently drops every event, which is a
 * quiet way for "a trace id clicks through to the log lines" to be false.
 *
 * <p><b>Three.</b> The instrumentation scope's {@code schema_url}, so the Collector's schema
 * processor can translate our attributes if semconv renames one — and it does: {@code
 * gen_ai.system} became {@code gen_ai.provider.name} inside a year, and the whole GenAI convention
 * set is still Development status. See {@link #otelTracer} for why an application is the only place
 * this can be set.
 */
@Configuration(proxyBeanMethods = false)
public class Telemetry {

  /**
   * The semantic-conventions revision this harness's {@code gen_ai.*} attributes were written and
   * audited against — the o11y spec's §8b audit references semantic-conventions v1.44.0. A schema
   * URL is always {@code https://opentelemetry.io/schemas/<version>}.
   */
  static final String SEMCONV_SCHEMA_URL = "https://opentelemetry.io/schemas/1.44.0";

  /**
   * The instrumentation scope's name: the library whose spans these are, not the service. {@code
   * service.name} is a Resource attribute and is set from {@code spring.application.name}.
   */
  private static final String INSTRUMENTATION_SCOPE = "org.jwcarman.nessy";

  /**
   * The OpenTelemetry {@link Tracer} Micrometer's bridge turns every harness observation into a
   * span through — supplied here rather than left to Boot, purely so it can carry a schema URL.
   *
   * <p>This is the ONLY place the schema URL can be set. Micrometer's {@code Observation} /{@code
   * ObservationRegistry} API has no notion of one — it has no notion of OpenTelemetry at all, which
   * is the point of the one-seam ruling — and {@code micrometer-tracing-bridge-otel} wraps whatever
   * {@code Tracer} it is handed, so every span inherits that tracer's instrumentation scope, schema
   * URL included. Boot's own {@code OpenTelemetryTracingAutoConfiguration} builds it as {@code
   * openTelemetry.getTracer("org.springframework.boot", SpringBootVersion.getVersion())}, with no
   * schema URL, under {@code @ConditionalOnMissingBean} — so declaring this bean replaces it.
   *
   * <p>That it lives application-side rather than in {@code nessy-agent} is not a compromise:
   * {@code nessy-agent} depends on {@code micrometer-observation} alone and never sees an
   * OpenTelemetry type, so it has nothing to stamp.
   */
  @Bean
  public Tracer otelTracer(OpenTelemetry openTelemetry) {
    return openTelemetry
        .tracerBuilder(INSTRUMENTATION_SCOPE)
        .setSchemaUrl(SEMCONV_SCHEMA_URL)
        .build();
  }

  /** Adds the token-usage handler to whichever {@code ObservationRegistry} Boot built. */
  @Bean
  public ObservationRegistryCustomizer<ObservationRegistry> tokenUsage(MeterRegistry meters) {
    return registry ->
        registry.observationConfig().observationHandler(new TokenUsageHandler(meters));
  }

  /**
   * Hands the logback appender the SDK, on {@code ApplicationReadyEvent} rather than at bean
   * construction: the appender is live from the first log line of startup, long before any bean
   * exists, and installing early would mean racing Boot's own logging system. Lines logged before
   * this point are held in the appender's own buffer and flushed on install.
   */
  @Bean
  public ApplicationListener<ApplicationReadyEvent> openTelemetryAppenderInstaller(
      OpenTelemetry openTelemetry) {
    return event -> OpenTelemetryAppender.install(openTelemetry);
  }
}
