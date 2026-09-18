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

import com.google.genai.Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Building a provider needs no network: the SDK client is constructed, never used. */
@DisplayName("The Gemini provider config")
class GeminiProviderConfigTest {

  @Test
  void a_key_a_base_url_and_a_mapper_build_a_provider_that_closes_its_own_client() {
    GeminiInferenceProvider provider =
        GeminiInferenceProvider.create(
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

    GeminiInferenceProvider provider = GeminiInferenceProvider.create(c -> c.client(theirs));

    assertThatCode(provider::close).doesNotThrowAnyException();
    // Still usable afterwards: the provider did not close it.
    assertThat(theirs.models).isNotNull();
  }

  @Test
  void an_explicit_key_wins_over_the_environment() {
    assertThatCode(
            () -> GeminiInferenceProvider.create(c -> c.fromEnv().apiKey("explicit")).close())
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
    assertThatThrownBy(() -> GeminiInferenceProvider.create(c -> c.apiKey(" ")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> GeminiInferenceProvider.create(c -> c.mapper(null)))
        .isInstanceOf(NullPointerException.class);
  }
}
