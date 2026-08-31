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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

class GeminiModelProviderBootstrapTest {

  private final GeminiModelProviderBootstrap bootstrap = new GeminiModelProviderBootstrap();

  @Test
  void is_named_gemini() {
    assertThat(bootstrap.name()).isEqualTo("gemini");
  }

  @Test
  void reads_googles_documented_pair() {
    assertThat(bootstrap.environmentVariables())
        .containsExactlyInAnyOrder("GEMINI_API_KEY", "GOOGLE_API_KEY");
  }

  @Test
  void defaults_to_flash() {
    assertThat(bootstrap.defaultModelId()).isEqualTo("gemini-3.6-flash");
  }

  @Test
  void is_empty_when_neither_key_is_present() {
    assertThat(bootstrap.bootstrap(Map.of("ANTHROPIC_API_KEY", "not-mine"))).isEmpty();
  }

  @Test
  void builds_from_gemini_api_key() {
    var provider = bootstrap.bootstrap(Map.of("GEMINI_API_KEY", "fake-gemini-key"));

    assertThat(provider).containsInstanceOf(GeminiModelProvider.class);
  }

  @Test
  void builds_from_google_api_key_too() {
    var provider = bootstrap.bootstrap(Map.of("GOOGLE_API_KEY", "fake-google-key"));

    assertThat(provider).containsInstanceOf(GeminiModelProvider.class);
  }

  @Test
  void both_present_still_builds_one_provider() {
    var provider =
        bootstrap.bootstrap(
            Map.of("GEMINI_API_KEY", "fake-gemini", "GOOGLE_API_KEY", "fake-google"));

    assertThat(provider).containsInstanceOf(GeminiModelProvider.class);
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

    assertThat(registered).containsExactly(GeminiModelProviderBootstrap.class);
  }
}
