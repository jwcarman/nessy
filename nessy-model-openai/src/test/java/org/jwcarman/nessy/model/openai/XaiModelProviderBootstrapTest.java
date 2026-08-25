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
import org.junit.jupiter.api.Test;

class XaiModelProviderBootstrapTest {

  private final XaiModelProviderBootstrap bootstrap = new XaiModelProviderBootstrap();

  @Test
  void is_named_xai() {
    assertThat(bootstrap.name()).isEqualTo("xai");
  }

  @Test
  void reads_only_the_xai_api_key() {
    assertThat(bootstrap.environmentVariables()).containsExactly("XAI_API_KEY");
  }

  @Test
  void defaults_to_grok() {
    assertThat(bootstrap.defaultModelId()).isEqualTo("grok-4.6");
  }

  @Test
  void is_empty_when_the_key_is_absent() {
    assertThat(bootstrap.bootstrap(Map.of("OPENAI_API_KEY", "not-mine"))).isEmpty();
  }

  @Test
  void a_stray_openai_base_url_still_builds_a_provider_and_is_not_declared() {
    // xAI's URL is fixed by BASE_URL and OpenAiModelProvider does not expose it, so the fixed URL
    // is pinned by the constant, not by this test. What this pins: an OPENAI_BASE_URL alongside
    // an xAI key neither breaks construction nor appears among the variables xAI declares.
    var provider =
        bootstrap.bootstrap(
            Map.of("XAI_API_KEY", "fake-xai-key", "OPENAI_BASE_URL", "http://should.be.ignored"));

    assertThat(provider).containsInstanceOf(OpenAiModelProvider.class);
    assertThat(bootstrap.environmentVariables()).doesNotContain("OPENAI_BASE_URL");
  }

  @Test
  void builds_an_openai_wire_provider_when_the_key_is_present() {
    var provider = bootstrap.bootstrap(Map.of("XAI_API_KEY", "fake-xai-key"));

    assertThat(provider).containsInstanceOf(OpenAiModelProvider.class);
  }

  @Test
  void rejects_a_null_env() {
    assertThatThrownBy(() -> bootstrap.bootstrap(null)).isInstanceOf(NullPointerException.class);
  }
}
