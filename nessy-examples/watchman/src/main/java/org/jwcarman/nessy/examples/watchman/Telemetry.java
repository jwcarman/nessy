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
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two things the application must contribute to its own observability (spec §2.3).
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
 */
@Configuration(proxyBeanMethods = false)
public class Telemetry {

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
