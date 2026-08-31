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
package org.jwcarman.nessy.model.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

class OpenAiModelProviderBootstrapTest {

  private final OpenAiModelProviderBootstrap bootstrap = new OpenAiModelProviderBootstrap();

  @Test
  void is_named_openai() {
    assertThat(bootstrap.name()).isEqualTo("openai");
  }

  @Test
  void reads_the_key_and_the_optional_base_url() {
    assertThat(bootstrap.environmentVariables())
        .containsExactlyInAnyOrder("OPENAI_API_KEY", "OPENAI_BASE_URL");
  }

  @Test
  void defaults_to_gpt_4o_mini() {
    assertThat(bootstrap.defaultModelId()).isEqualTo("gpt-4o-mini");
  }

  @Test
  void is_empty_when_the_key_is_absent() {
    assertThat(bootstrap.bootstrap(Map.of("ANTHROPIC_API_KEY", "not-mine"))).isEmpty();
  }

  @Test
  void a_base_url_alone_is_not_credentials() {
    // Pinned separately from the case above because it is the one a local-runtime user hits:
    // OPENAI_BASE_URL set, key forgotten. The answer must be "no credentials", never a provider
    // pointed at the URL with no key.
    assertThat(bootstrap.bootstrap(Map.of("OPENAI_BASE_URL", "http://127.0.0.1:1234/v1")))
        .isEmpty();
  }

  @Test
  void builds_an_openai_provider_when_the_key_is_present() {
    var provider = bootstrap.bootstrap(Map.of("OPENAI_API_KEY", "fake-openai-key"));

    assertThat(provider).containsInstanceOf(OpenAiModelProvider.class);
  }

  @Test
  void builds_an_openai_provider_with_a_base_url_layered_on() {
    var provider =
        bootstrap.bootstrap(
            Map.of("OPENAI_API_KEY", "lm-studio", "OPENAI_BASE_URL", "http://127.0.0.1:1234/v1"));

    assertThat(provider).containsInstanceOf(OpenAiModelProvider.class);
  }

  @Test
  void rejects_a_null_env() {
    assertThatThrownBy(() -> bootstrap.bootstrap(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void this_module_registers_openai_and_xai_and_nothing_else() {
    var registered =
        ServiceLoader.load(ModelProviderBootstrap.class).stream()
            .map(ServiceLoader.Provider::type)
            .toList();

    assertThat(registered)
        .containsExactlyInAnyOrder(
            OpenAiModelProviderBootstrap.class, XaiModelProviderBootstrap.class);
  }
}
