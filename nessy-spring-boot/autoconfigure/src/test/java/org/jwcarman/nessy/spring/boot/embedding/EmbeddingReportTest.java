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
package org.jwcarman.nessy.spring.boot.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.nessy.spring.boot.NessyProperties;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * What the startup lines say once every registered {@code EmbeddingProvider} bean exists: every
 * embedder by id, the default, and never a key or a property's value.
 *
 * <p>The house test logging config (root {@code WARN}) would swallow these lines, which are INFO;
 * this class's logger is turned up for the duration of these tests alone.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("The embedding report")
class EmbeddingReportTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EmbeddingProvidersAutoConfiguration.class))
          .withBean(ObservationRegistry.class, ObservationRegistry::create);

  @BeforeEach
  void turnOnInfoLogging() {
    logger().setLevel(Level.INFO);
  }

  @AfterEach
  void restoreLogging() {
    logger().setLevel(null);
  }

  private static Logger logger() {
    return (Logger) LoggerFactory.getLogger(EmbeddingReport.class);
  }

  private static void report(AssertableApplicationContext context) {
    new EmbeddingReport(
            context.getBeanProvider(ResolvedEmbedders.class),
            context,
            context.getBean(NessyProperties.class))
        .afterSingletonsInstantiated();
  }

  @Test
  void the_embedders_line_names_every_embedder_and_never_a_key_or_a_value(CapturedOutput output) {
    runner
        .withPropertyValues(
            "openai.api-key=sk-super-secret",
            "voyage.api-key=pa-super-secret",
            "nessy.embedders.voyage.properties.voyage.truncation=false")
        .run(
            context -> {
              report(context);

              assertThat(output)
                  .contains(
                      "NESSY EMBEDDING: embedders: openai (openai, the vendor's own endpoint,"
                          + " vendor openai); voyage (voyage, https://api.voyageai.com/v1, vendor"
                          + " voyage, properties [voyage.truncation])")
                  .doesNotContain("sk-super-secret")
                  .doesNotContain("pa-super-secret")
                  .doesNotContain("truncation=false");
            });
  }

  @Test
  void the_default_line_names_the_pair_and_the_width(CapturedOutput output) {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test",
            "nessy.embedder=voyage",
            "nessy.embedding-model=voyage-3.5",
            "nessy.embedding-dimension=1024")
        .run(
            context -> {
              report(context);

              assertThat(output)
                  .contains("NESSY EMBEDDING: default: voyage / voyage-3.5, 1024 wide");
            });
  }

  @Test
  void the_default_line_without_a_width_names_the_pair(CapturedOutput output) {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test", "nessy.embedder=voyage", "nessy.embedding-model=voyage-3.5")
        .run(
            context -> {
              report(context);

              assertThat(output)
                  .contains("NESSY EMBEDDING: default: voyage / voyage-3.5")
                  .doesNotContain(" wide");
            });
  }

  @Test
  void with_nothing_registered_it_says_stores_rank_by_recency(CapturedOutput output) {
    runner.run(
        context -> {
          report(context);

          assertThat(output)
              .contains("NESSY EMBEDDING: no embedder is configured; stores rank by recency");
        });
  }

  @Test
  void with_embedders_and_no_default_it_says_every_store_names_its_own(CapturedOutput output) {
    runner
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              report(context);

              assertThat(output)
                  .contains("NESSY EMBEDDING: no default embedder; every store names its own");
            });
  }

  @Test
  void an_application_bean_prints_the_vendor_it_reports(CapturedOutput output) {
    runner
        .withUserConfiguration(EmbeddingProvidersAutoConfigurationTest.AScriptedEmbedder.class)
        .run(
            context -> {
              report(context);

              assertThat(output).contains("NESSY EMBEDDING: embedders: scripted (vendor scripted)");
            });
  }

  @Test
  void a_custom_embedder_prints_the_vendor_its_bean_reports(CapturedOutput output) {
    runner
        .withPropertyValues(
            "nessy.embedders.local.wire=openai",
            "nessy.embedders.local.base-url=http://localhost:1234/v1",
            "nessy.embedders.local.api-key=lm-studio",
            "nessy.embedders.local.vendor=lmstudio")
        .run(
            context -> {
              report(context);

              assertThat(output)
                  .contains("local (openai, http://localhost:1234/v1, vendor lmstudio)");
            });
  }
}
