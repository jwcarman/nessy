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
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
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
@Tag("container")
class TelemetryWiringTest {

  @Autowired private ApplicationContext context;

  @Autowired private ObservationRegistry observations;

  @Autowired private MeterRegistry meters;

  /**
   * Exactly one, not merely at least one (final review, finding #10). The name said "single" and
   * the assertion said {@code isNotEmpty}, which would have passed with two competing exporters
   * wired by two different autoconfigurations — the kind of duplicate that silently doubles export
   * volume and makes every metric read twice as busy as the box actually is. {@code
   * context.getBean(type)} would then have thrown on the very next line, so the bug was narrow, but
   * the assertion should say what the method's name promises.
   */
  private void hasSingleBeanOf(Class<?> type) {
    assertThat(context.getBeanNamesForType(type)).hasSize(1);
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

  /**
   * And none of the three points at a collector during a build (soak finding F4, 2026-08-26). This
   * context is the real one, with real exporters, so on the soak host every {@code mvn verify} used
   * to inject spans under {@code service.name=watchman} that were indistinguishable from a real
   * round — confirmed in Tempo, not theorised. {@code src/test/resources/application-scripted.yml}
   * redirects all three to a closed port; this is the assertion that turns losing that file into a
   * red build rather than a quietly polluted dashboard.
   */
  private void isWiredAndNotPointedAtACollector(String property) {
    String value = context.getEnvironment().getProperty(property);
    assertThat(value).isNotBlank().doesNotContain(":4318").doesNotContain(":4317");
  }

  @Test
  void the_three_otlp_endpoints_are_configured_independently() {
    isWiredAndNotPointedAtACollector("management.otlp.metrics.export.url");
    // Boot 4.1's names, which are NOT the management.otlp.* ones metrics still uses. The old
    // spelling binds to nothing and creates no exporter, silently.
    isWiredAndNotPointedAtACollector("management.opentelemetry.tracing.export.otlp.endpoint");
    // The one an operator forgets: without its own override, pointing OTLP_TRACES_URL at a remote
    // box silently keeps shipping logs to localhost.
    isWiredAndNotPointedAtACollector("management.opentelemetry.logging.export.otlp.endpoint");
  }

  /**
   * The REAL assertion (fix round, 2026-08-26 — this used to assert that a {@code Telemetry} bean
   * existed, which is not the same claim at all: deleting the {@code observationHandler(...)}
   * registration left it green). {@link TokenUsageHandler} is deliberately NOT a bean — it is
   * registered on the registry's observation config — so the only honest place to look for it is
   * the registry's own handler list.
   */
  @Test
  void the_token_usage_handler_is_on_the_registry_the_harness_was_given() {
    assertThat(observations).isNotNull();
    assertThat(meters).isNotNull();
    assertThat(context.getBeansOfType(TokenUsageHandler.class)).isEmpty();

    // Micrometer keeps its handler list package-private, so the honest assertion is behavioural:
    // drive exactly the observation the handler claims to support through the registry the harness
    // was given, and look for the metric only that handler produces. Deleting the
    // observationHandler(...) registration in Telemetry turns this red.
    Observation.createNotStarted("gen_ai.client.operation.duration", observations)
        .lowCardinalityKeyValue("gen_ai.operation.name", "chat")
        .lowCardinalityKeyValue("gen_ai.provider.name", "anthropic")
        .lowCardinalityKeyValue("gen_ai.request.model", "claude-test")
        .lowCardinalityKeyValue("error.type", "none")
        .highCardinalityKeyValue("gen_ai.usage.input_tokens", "120")
        .highCardinalityKeyValue("gen_ai.usage.output_tokens", "34")
        .start()
        .stop();

    assertThat(meters.find("gen_ai.client.token.usage").summaries())
        .isNotEmpty()
        .anySatisfy(
            summary -> {
              assertThat(summary.getId().getTag("gen_ai.token.type")).isEqualTo("input");
              assertThat(summary.totalAmount()).isEqualTo(120.0d);
            })
        .anySatisfy(
            summary -> {
              assertThat(summary.getId().getTag("gen_ai.token.type")).isEqualTo("output");
              assertThat(summary.totalAmount()).isEqualTo(34.0d);
            });
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
