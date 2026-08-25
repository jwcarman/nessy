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
package org.jwcarman.nessy.model.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/**
 * Drives the bootstrap through its env-map argument only — no real environment variable, no
 * network. Construction of {@link AnthropicModelProvider} from a fake key is offline, the same way
 * the retired env-provider test relied on.
 */
class AnthropicModelProviderBootstrapTest {

  private final AnthropicModelProviderBootstrap bootstrap = new AnthropicModelProviderBootstrap();

  @Test
  void is_named_anthropic() {
    assertThat(bootstrap.name()).isEqualTo("anthropic");
  }

  @Test
  void reads_only_the_anthropic_api_key() {
    assertThat(bootstrap.environmentVariables()).containsExactly("ANTHROPIC_API_KEY");
  }

  @Test
  void defaults_to_haiku() {
    assertThat(bootstrap.defaultModelId()).isEqualTo("claude-haiku-4-5-20251001");
  }

  @Test
  void is_empty_when_the_key_is_absent() {
    assertThat(bootstrap.bootstrap(Map.of("OPENAI_API_KEY", "not-mine"))).isEmpty();
  }

  @Test
  void builds_an_anthropic_provider_when_the_key_is_present() {
    var provider = bootstrap.bootstrap(Map.of("ANTHROPIC_API_KEY", "fake-anthropic-key"));

    assertThat(provider).containsInstanceOf(AnthropicModelProvider.class);
  }

  @Test
  void rejects_a_null_env() {
    assertThatThrownBy(() -> bootstrap.bootstrap(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void is_registered_for_service_loading() {
    var registered =
        ServiceLoader.load(ModelProviderBootstrap.class).stream()
            .map(ServiceLoader.Provider::type)
            .toList();

    assertThat(registered).containsExactly(AnthropicModelProviderBootstrap.class);
  }
}
