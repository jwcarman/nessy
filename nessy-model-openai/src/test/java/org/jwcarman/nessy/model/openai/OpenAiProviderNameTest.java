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

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;

/**
 * {@code gen_ai.provider.name} for this gateway (agentic-o11y spec §1.1), which is the one case
 * where the answer cannot be a constant on the class: {@link OpenAiModelProvider} serves every
 * OpenAI-compatible vendor, and {@link XaiModelProviderBootstrap} builds this very class against
 * {@code api.x.ai}. Semconv has a separate {@code x_ai} value, so the vendor identity is stamped by
 * whichever bootstrap read the key that named it — otherwise every xAI turn would be reported, and
 * billed in a dashboard, as an OpenAI one.
 */
class OpenAiProviderNameTest {

  @Test
  void a_plainly_built_gateway_reports_the_semconv_value_for_openai() {
    Model model = new OpenAiProviderConfig().apiKey("sk-test").build().model("gpt-5");

    assertThat(model.provider()).isEqualTo("openai");
    assertThat(OpenAiModelProvider.PROVIDER).isEqualTo("openai");
  }

  @Test
  void a_gateway_the_xai_bootstrap_built_reports_the_semconv_value_for_x_ai() {
    ModelProvider provider =
        new XaiModelProviderBootstrap()
            .bootstrap(Map.of("XAI_API_KEY", "fake-xai-key"))
            .orElseThrow();

    assertThat(provider.model("grok-4").provider()).isEqualTo("x_ai");
    assertThat(XaiModelProviderBootstrap.PROVIDER).isEqualTo("x_ai");
  }
}
