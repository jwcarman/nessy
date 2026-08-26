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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

/**
 * The whole point of this module is the dashboards, and the build was green with none of them
 * wired. This is the test that would have said so.
 *
 * <p>Boot 4 split tracing and OTLP autoconfiguration out of {@code
 * spring-boot-actuator-autoconfigure} into module jars that arrive only through {@code
 * spring-boot-starter-opentelemetry}. Declaring the Boot-3-shaped set of runtime libraries — {@code
 * micrometer-tracing-bridge-otel}, {@code opentelemetry-exporter-otlp}, {@code
 * micrometer-registry-otlp} — puts every class on the classpath and wires none of them. The
 * application starts, {@code management.otlp.tracing.*} and {@code
 * management.tracing.sampling.probability} bind to nothing, metrics keep working, and Tempo stays
 * empty forever. Nothing fails; there is simply no trace.
 *
 * <p>So each assertion here names a bean whose ABSENCE is a silent hole:
 *
 * <ul>
 *   <li>{@link OpenTelemetry} and {@link SdkTracerProvider} — no SDK means the tracing properties
 *       are decoration.
 *   <li>{@link SpanExporter} — a tracer with nowhere to send spans is a tracer nobody sees.
 *   <li>{@link Tracer} — Micrometer's bridge, i.e. the thing the harness's observations become
 *       spans through.
 *   <li>{@link SdkLoggerProvider} and {@link LogRecordExporter} — without them {@code
 *       logback-spring.xml}'s OTLP appender ships nothing, and "a trace id clicks through" is
 *       false.
 * </ul>
 *
 * <p>No collector is running during this test and none needs to be: exporters are built eagerly and
 * fail lazily, which is also why a box with the LGTM container stopped keeps doing rounds.
 */
@SpringBootTest(
    classes = {WatchmanApplication.class, TelemetryWiringTest.Host.class},
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "watchman.scheduling.enabled=false",
      "watchman.user=ops",
      "watchman.password=lan-only",
      "watchman.notes-dir=target/telemetry-test-notes"
    })
@ActiveProfiles("scripted")
class TelemetryWiringTest {

  @Autowired private ApplicationContext context;

  @Autowired private ObservationRegistry observations;

  @Autowired private MeterRegistry meters;

  private void hasSingleBeanOf(Class<?> type) {
    assertThat(context.getBeanNamesForType(type)).isNotEmpty();
    assertThat(context.getBean(type)).isNotNull();
  }

  @Test
  void the_opentelemetry_sdk_is_actually_wired_and_not_merely_on_the_classpath() {
    hasSingleBeanOf(OpenTelemetry.class);
    hasSingleBeanOf(SdkTracerProvider.class);
  }

  @Test
  void spans_have_somewhere_to_go() {
    hasSingleBeanOf(SpanExporter.class);
    hasSingleBeanOf(Tracer.class);
  }

  @Test
  void logs_have_somewhere_to_go_so_a_trace_id_can_click_through() {
    hasSingleBeanOf(SdkLoggerProvider.class);
    hasSingleBeanOf(LogRecordExporter.class);
  }

  @Test
  void the_three_otlp_endpoints_are_configured_independently() {
    assertThat(context.getEnvironment().getProperty("management.otlp.metrics.export.url"))
        .isNotBlank();
    // Boot 4.1's names, which are NOT the management.otlp.* ones metrics still uses. The old
    // spelling binds to nothing and creates no exporter, silently.
    assertThat(
            context
                .getEnvironment()
                .getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
        .isNotBlank();
    // The one an operator forgets: without its own override, pointing OTLP_TRACES_URL at a remote
    // box silently keeps shipping logs to localhost.
    assertThat(
            context
                .getEnvironment()
                .getProperty("management.opentelemetry.logging.export.otlp.endpoint"))
        .isNotBlank();
  }

  @Test
  void the_token_usage_handler_is_on_the_registry_the_harness_was_given() {
    assertThat(observations).isNotNull();
    assertThat(meters).isNotNull();
    assertThat(context.getBeansOfType(TokenUsageHandler.class)).isEmpty();
    // Registered as a handler rather than as a bean — assert the customizer that does it exists.
    assertThat(context.getBeanNamesForType(Telemetry.class)).isNotEmpty();
    assertThat(context.getBean(Telemetry.class)).isNotNull();
  }

  /** A database, because the page's projection needs one; nothing here touches the host. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Host {

    @Bean
    DataSource dataSource() {
      return WatchmanPostgres.dataSource();
    }

    @Bean
    CommandRunner commandRunner() {
      return new FakeRunner();
    }
  }
}
