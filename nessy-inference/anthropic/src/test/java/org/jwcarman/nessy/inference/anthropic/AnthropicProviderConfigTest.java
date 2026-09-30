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
package org.jwcarman.nessy.inference.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.anthropic.client.AnthropicClient;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.VendorProperty;

/**
 * The transport timeout setter (design record 2026-09-25-locks-as-plumbing-design.md §5).
 *
 * <p>Building a provider needs no network: the SDK client is constructed, never used. The client's
 * own {@code Timeout} is not readable back through {@link AnthropicClient} without reflection or a
 * network call (verified against anthropic-java 2.62.0: the interface exposes no accessor for it),
 * so these tests assert on the setter's own validation and on the config's fluent state rather than
 * on a value read back from a built client.
 */
@DisplayName("The Anthropic provider config's transport timeout")
class AnthropicProviderConfigTest {

  @Test
  void a_null_timeout_is_rejected() {
    assertThatThrownBy(() -> AnthropicInferenceProvider.of(c -> c.apiKey("test-key").timeout(null)))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_zero_timeout_is_rejected() {
    assertThatThrownBy(
            () -> AnthropicInferenceProvider.of(c -> c.apiKey("test-key").timeout(Duration.ZERO)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_negative_timeout_is_rejected() {
    assertThatThrownBy(
            () ->
                AnthropicInferenceProvider.of(
                    c -> c.apiKey("test-key").timeout(Duration.ofSeconds(-1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_positive_timeout_builds_a_provider_that_closes_its_own_client() {
    AnthropicInferenceProvider provider =
        AnthropicInferenceProvider.of(c -> c.apiKey("test-key").timeout(Duration.ofMinutes(6)));

    assertThat(provider.name()).isEqualTo("Anthropic");
    assertThatCode(provider::close).doesNotThrowAnyException();
  }

  @Test
  void a_timeout_applies_on_the_from_env_build_path_too() {
    assertThatCode(
            () ->
                AnthropicInferenceProvider.of(
                        c -> c.fromEnv().apiKey("explicit").timeout(Duration.ofMinutes(6)))
                    .close())
        .doesNotThrowAnyException();
  }

  @Test
  void a_timeout_alongside_a_supplied_client_leaves_that_client_unclosed_here() {
    AtomicInteger closes = new AtomicInteger();
    AnthropicClient supplied = recordingClient(closes);

    AnthropicInferenceProvider provider =
        AnthropicInferenceProvider.of(c -> c.client(supplied).timeout(Duration.ofMinutes(6)));
    provider.close();

    assertThat(closes).hasValue(0);
  }

  private static AnthropicClient recordingClient(AtomicInteger closes) {
    return (AnthropicClient)
        Proxy.newProxyInstance(
            AnthropicClient.class.getClassLoader(),
            new Class<?>[] {AnthropicClient.class},
            (proxy, method, args) -> {
              if ("close".equals(method.getName())) {
                closes.incrementAndGet();
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  @Test
  void a_budget_setter_and_a_budget_property_fail_at_build_naming_both() {
    Customizer<AnthropicProviderConfig> customizer =
        c ->
            c.apiKey("test-key")
                .thinking(true)
                .thinkingBudget(4096)
                .property("anthropic.thinking.budget_tokens", "8192");

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("thinkingBudget(int)")
        .hasMessageContaining("'anthropic.thinking.budget_tokens'");
  }

  /** "Whatever the values": a setter set to off still says something about the same field. */
  @Test
  void thinking_off_and_a_thinking_type_property_fail_at_build_naming_both() {
    Customizer<AnthropicProviderConfig> customizer =
        c ->
            c.apiKey("test-key")
                .thinking(false)
                .properties(Map.of("anthropic.thinking.type", "adaptive"));

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("thinking(boolean)")
        .hasMessageContaining("'anthropic.thinking.type'");
  }

  @Test
  void thinking_off_and_a_budget_property_fail_at_build_naming_both() {
    Customizer<AnthropicProviderConfig> customizer =
        c ->
            c.apiKey("test-key")
                .thinking(false)
                .property("anthropic.thinking.budget_tokens", "2048");

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("thinking(boolean)")
        .hasMessageContaining("'anthropic.thinking.budget_tokens'");
  }

  @Test
  void a_budget_setter_and_a_thinking_type_property_fail_at_build_naming_both() {
    Customizer<AnthropicProviderConfig> customizer =
        c ->
            c.apiKey("test-key")
                .thinkingBudget(4096)
                .property("anthropic.thinking.type", "adaptive");

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("thinkingBudget(int)")
        .hasMessageContaining("'anthropic.thinking.type'");
  }

  @Test
  void a_bare_budget_setter_alone_still_builds() {
    Customizer<AnthropicProviderConfig> customizer = c -> c.apiKey("test-key").thinkingBudget(4096);

    assertThatCode(() -> AnthropicInferenceProvider.of(customizer)).doesNotThrowAnyException();
  }

  @Test
  void thinking_on_beside_a_budget_property_still_builds() {
    Customizer<AnthropicProviderConfig> customizer =
        c ->
            c.apiKey("test-key")
                .thinking(true)
                .property("anthropic.thinking.budget_tokens", "2048");

    assertThatCode(() -> AnthropicInferenceProvider.of(customizer)).doesNotThrowAnyException();
  }

  @Test
  void a_caching_setter_and_a_ttl_property_fail_at_build_naming_both() {
    Customizer<AnthropicProviderConfig> customizer =
        c ->
            c.apiKey("test-key")
                .promptCaching(PromptCaching.OFF)
                .property("anthropic.cache_control.ttl", "5m");

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("promptCaching(PromptCaching)")
        .hasMessageContaining("'anthropic.cache_control.ttl'");
  }

  @Test
  void enabled_without_a_budget_at_the_provider_is_refused_at_build() {
    Customizer<AnthropicProviderConfig> customizer =
        c -> c.apiKey("test-key").property("anthropic.thinking.type", "enabled");

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'anthropic.thinking.budget_tokens'");
  }

  @Test
  void a_thinking_type_that_is_not_enabled_adaptive_or_disabled_is_refused_at_build() {
    Customizer<AnthropicProviderConfig> customizer =
        c -> c.apiKey("test-key").property("anthropic.thinking.type", "interleaved");

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "property 'anthropic.thinking.type' must be enabled, adaptive or disabled,"
                + " was 'interleaved'");
  }

  @Test
  void a_property_under_another_prefix_is_refused_at_build_naming_the_prefix() {
    Customizer<AnthropicProviderConfig> customizer =
        c -> c.apiKey("test-key").property("openai.reasoning.effort", "high");

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.reasoning.effort'")
        .hasMessageContaining("'anthropic.'");
  }

  @Test
  void a_typed_property_is_stored_under_its_name_and_refused_like_the_string_form() {
    Customizer<AnthropicProviderConfig> customizer =
        c ->
            c.apiKey("test-key")
                .property(VendorProperty.ofBoolean("openai.reasoning.effort"), true);

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.reasoning.effort'")
        .hasMessageContaining("'anthropic.'");
  }

  @Test
  void an_unsupported_property_is_warned_once_at_build_and_the_provider_still_builds() {
    Customizer<AnthropicProviderConfig> customizer =
        c -> c.apiKey("test-key").property("anthropic.max_tokens", "10");
    var built = new AnthropicInferenceProvider[1];

    List<ILoggingEvent> events =
        LogCapture.during(
            AnthropicProperties.class, () -> built[0] = AnthropicInferenceProvider.of(customizer));

    assertThat(LogCapture.warnings(events))
        .singleElement()
        .asString()
        .contains("'anthropic.max_tokens'")
        .contains("anthropic.service_tier");
    assertThat(built[0]).isNotNull();
    built[0].close();
  }
}
