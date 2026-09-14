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
package org.jwcarman.nessy.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gemini's registration as a Boot citizen: what {@code GEMINI_API_KEY} / {@code GOOGLE_API_KEY} in
 * the environment — read here as the properties Boot's relaxed binding turns them into —
 * contribute, and what they do not.
 *
 * <p><b>Why most of these tests assert a startup failure rather than a built bean.</b> The bean
 * this auto-configuration publishes is {@code
 * GeminiModelProvider.create(GeminiProviderConfig::fromEnv)}, and {@code fromEnv()} reads the REAL
 * process environment ({@link System#getenv}) directly, not Spring's {@code Environment} — the same
 * seam-integrity choice {@code GeminiModelProviderTest} documents, and the same reason that suite
 * never sets a fake key and expects {@code fromEnv()} to see it either. A test cannot set a real
 * environment variable, so what IS honestly assertable offline is that the CONDITION lets the bean
 * method run at all: with a key property set, the context reaches {@code fromEnv()}, which then
 * fails on its own well-known missing-real-credentials message — proof the gate did not reject it,
 * which is exactly the thing this class exists to prove for {@code google.api-key} alone.
 */
@DisplayName("Gemini's auto-configuration")
class GeminiAutoConfigurationTest {

  private static final String FROM_ENV_MESSAGE =
      "GEMINI_API_KEY (or GOOGLE_API_KEY) environment variable is not set; call apiKey(...) or"
          + " client(...) instead";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(GeminiAutoConfiguration.class));

  @Test
  @DisplayName("with a Gemini key present, the gate matches and fromEnv() is reached")
  void with_a_gemini_key_present_the_gate_matches() {
    runner
        .withPropertyValues("gemini.api-key=gm-test")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasRootCauseMessage(FROM_ENV_MESSAGE);
            });
  }

  @Test
  @DisplayName("with only a Google key present, the gate matches and fromEnv() is reached")
  void with_only_a_google_key_present_the_gate_matches() {
    runner
        .withPropertyValues("google.api-key=g-test")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasRootCauseMessage(FROM_ENV_MESSAGE);
            });
  }

  @Test
  @DisplayName("with no key at all, it contributes nothing and the context starts cleanly")
  void with_no_key_at_all_it_contributes_nothing() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(ModelProvider.class);
        });
  }

  @Nested
  @DisplayName("when the application already supplies its own ModelProvider")
  class WhenTheApplicationSuppliesItsOwn {

    /**
     * The user's own bean makes {@code @ConditionalOnMissingBean} fail before the {@code @Bean}
     * method — and therefore {@code fromEnv()} — ever runs, so this is fully testable offline with
     * no real environment variable needed: the context starts cleanly either way.
     */
    @Test
    @DisplayName(
        "it backs off entirely, the application's bean unmoved, without reaching fromEnv()")
    void it_backs_off_entirely() {
      runner
          .withPropertyValues("gemini.api-key=gm-test", "google.api-key=g-test")
          .withUserConfiguration(AModelProvider.class)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ModelProvider.class);
                assertThat(context.getBean(ModelProvider.class)).isSameAs(AModelProvider.INSTANCE);
              });
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AModelProvider {

    static final ModelProvider INSTANCE = id -> null;

    @Bean
    ModelProvider models() {
      return INSTANCE;
    }
  }
}
