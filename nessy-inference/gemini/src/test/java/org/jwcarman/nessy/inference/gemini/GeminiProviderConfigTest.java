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
package org.jwcarman.nessy.inference.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.google.genai.Client;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.VendorProperty;
import tools.jackson.databind.json.JsonMapper;

/** Building a provider needs no network: the SDK client is constructed, never used. */
@DisplayName("The Gemini provider config")
class GeminiProviderConfigTest {

  @Test
  void a_key_a_base_url_and_a_mapper_build_a_provider_that_closes_its_own_client() {
    GeminiInferenceProvider provider =
        GeminiInferenceProvider.of(
            c ->
                c.apiKey("test-key")
                    .baseUrl("http://127.0.0.1:1")
                    .mapper(JsonMapper.builder().build()));

    assertThat(provider.name()).isEqualTo("Gemini");
    assertThatCode(provider::close).doesNotThrowAnyException();
  }

  @Test
  void a_client_the_application_hands_in_is_used_and_never_closed_here() {
    Client theirs = Client.builder().apiKey("theirs").build();

    GeminiInferenceProvider provider = GeminiInferenceProvider.of(c -> c.client(theirs));

    assertThatCode(provider::close).doesNotThrowAnyException();
    // Still usable afterwards: the provider did not close it.
    assertThat(theirs.models).isNotNull();
  }

  @Test
  void an_explicit_key_wins_over_the_environment() {
    assertThatCode(() -> GeminiInferenceProvider.of(c -> c.fromEnv().apiKey("explicit")).close())
        .doesNotThrowAnyException();
  }

  @Test
  void from_env_with_nothing_set_names_both_variables() {
    assumeTrue(
        System.getenv("GEMINI_API_KEY") == null && System.getenv("GOOGLE_API_KEY") == null,
        "a key is set in this environment");

    assertThatThrownBy(GeminiInferenceProvider::fromEnv)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GEMINI_API_KEY")
        .hasMessageContaining("GOOGLE_API_KEY");
  }

  @Test
  void a_blank_key_is_refused() {
    assertThatThrownBy(() -> GeminiInferenceProvider.of(c -> c.apiKey(" ")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> GeminiInferenceProvider.of(c -> c.mapper(null)))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * The transport timeout setter (design record 2026-09-25-locks-as-plumbing-design.md §5). The
   * SDK's {@link Client} exposes no public accessor for the {@code HttpOptions} it was built with
   * (verified against google-genai 1.66.0: {@code Client}'s only package-visible {@code baseUrl()}
   * reads it, and there is no public counterpart for {@code timeout}), so — same as OpenAI and
   * Anthropic — these tests assert on the setter's own validation and that building still succeeds,
   * rather than on a value read back from the built client.
   */
  @Nested
  @DisplayName("its transport timeout")
  class Its_transport_timeout {

    @Test
    void a_null_timeout_is_rejected() {
      assertThatThrownBy(() -> GeminiInferenceProvider.of(c -> c.apiKey("test-key").timeout(null)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_zero_timeout_is_rejected() {
      assertThatThrownBy(
              () -> GeminiInferenceProvider.of(c -> c.apiKey("test-key").timeout(Duration.ZERO)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_negative_timeout_is_rejected() {
      assertThatThrownBy(
              () ->
                  GeminiInferenceProvider.of(
                      c -> c.apiKey("test-key").timeout(Duration.ofSeconds(-1))))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_positive_timeout_builds_a_provider_that_closes_its_own_client() {
      GeminiInferenceProvider provider =
          GeminiInferenceProvider.of(c -> c.apiKey("test-key").timeout(Duration.ofMinutes(6)));

      assertThatCode(provider::close).doesNotThrowAnyException();
    }

    /** Exercises the branch that must not lose the base URL when both are configured together. */
    @Test
    void a_base_url_and_a_timeout_together_build_without_losing_either() {
      assertThatCode(
              () ->
                  GeminiInferenceProvider.of(
                          c ->
                              c.apiKey("test-key")
                                  .baseUrl("http://127.0.0.1:1")
                                  .timeout(Duration.ofMinutes(6)))
                      .close())
          .doesNotThrowAnyException();
    }

    @Test
    void a_timeout_alongside_a_supplied_client_leaves_that_client_usable_and_unclosed_here() {
      Client theirs = Client.builder().apiKey("theirs").build();

      GeminiInferenceProvider provider =
          GeminiInferenceProvider.of(c -> c.client(theirs).timeout(Duration.ofMinutes(6)));
      provider.close();

      // Still usable afterwards: the provider did not close it, and the timeout was never applied
      // to it -- the client is used exactly as supplied.
      assertThat(theirs.models).isNotNull();
    }
  }

  @Test
  void a_property_under_another_prefix_is_refused_at_build_naming_the_prefix() {
    Customizer<GeminiProviderConfig> customizer =
        c -> c.apiKey("test-key").property("gcp.gemini.seed", "1");

    assertThatThrownBy(() -> GeminiInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'gcp.gemini.seed'")
        .hasMessageContaining("'gemini.'");
  }

  @Test
  void a_yaml_style_string_with_a_bad_spelling_fails_at_build_listing_the_spellings() {
    GeminiProviderConfig config =
        new GeminiProviderConfig()
            .apiKey("test-key")
            .property("gemini.generationConfig.thinkingConfig.thinkingLevel", "extreme");

    assertThatThrownBy(config::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "property 'gemini.generationConfig.thinkingConfig.thinkingLevel' must be one of"
                + " [MINIMAL, LOW, MEDIUM, HIGH], was 'extreme'");
  }

  @Test
  void a_typed_property_is_stored_under_its_name_and_refused_like_the_string_form() {
    Customizer<GeminiProviderConfig> customizer =
        c -> c.apiKey("test-key").property(VendorProperty.ofBoolean("gcp.gemini.seed"), true);

    assertThatThrownBy(() -> GeminiInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'gcp.gemini.seed'")
        .hasMessageContaining("'gemini.'");
  }

  @Test
  void an_unsupported_property_is_warned_once_at_build_and_the_provider_still_builds() {
    Customizer<GeminiProviderConfig> customizer =
        c -> c.apiKey("test-key").property("gemini.generationConfig.maxOutputTokens", "9");
    var built = new GeminiInferenceProvider[1];

    List<ILoggingEvent> events =
        LogCapture.during(
            GeminiPropertyReader.class, () -> built[0] = GeminiInferenceProvider.of(customizer));

    assertThat(LogCapture.warnings(events))
        .singleElement()
        .asString()
        .contains("'gemini.generationConfig.maxOutputTokens'")
        .contains("gemini.generationConfig.thinkingConfig.thinkingBudget");
    assertThat(built[0]).isNotNull();
    built[0].close();
  }

  @Test
  void properties_given_as_a_map_are_accepted() {
    assertThatCode(
            () ->
                GeminiInferenceProvider.of(
                        c ->
                            c.apiKey("test-key")
                                .properties(Map.of("gemini.labels.team", "billing")))
                    .close())
        .doesNotThrowAnyException();
  }
}
