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
package org.jwcarman.nessy.spring.boot.inference;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * What the startup line says once every registered {@code InferenceProvider} bean exists: every
 * one, by id, and never the key.
 *
 * <p>The house test logging config (root {@code WARN}) would swallow the providers line, which is
 * INFO; this class's logger is turned up for the duration of these tests alone.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("The inference report")
class InferenceReportTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(InferenceProvidersAutoConfiguration.class))
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
    return (Logger) LoggerFactory.getLogger(InferenceReport.class);
  }

  @Test
  void the_providers_line_names_every_registered_provider_and_never_the_key(CapturedOutput output) {
    runner
        .withPropertyValues("openai.api-key=sk-super-secret", "xai.api-key=xai-super-secret")
        .run(
            context -> {
              InferenceReport report =
                  new InferenceReport(context.getBeanProvider(ResolvedProviders.class), context);
              report.afterSingletonsInstantiated();

              assertThat(output)
                  .contains(
                      "NESSY INFERENCE: providers: openai (chat-completions, the vendor's own"
                          + " endpoint, vendor openai); xai (chat-completions,"
                          + " https://api.x.ai/v1, vendor x_ai)")
                  .doesNotContain("sk-super-secret")
                  .doesNotContain("xai-super-secret");
            });
  }

  @Test
  void nothing_configured_warns(CapturedOutput output) {
    runner.run(
        context -> {
          InferenceReport report =
              new InferenceReport(context.getBeanProvider(ResolvedProviders.class), context);
          report.afterSingletonsInstantiated();

          assertThat(output).contains("NESSY INFERENCE: no provider is configured");
        });
  }
}
