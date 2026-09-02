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

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.spi.model.Model;

/**
 * {@code gen_ai.provider.name} for this vendor (agentic-o11y spec §1.1). One of the OpenTelemetry
 * GenAI semantic conventions' pinned strings, so it is a compatibility surface with whatever
 * dashboard groups by vendor.
 *
 * <p>This gateway is SHARED with xAI, which reaches the same Chat Completions wire at {@code
 * api.x.ai} and has its own {@code x_ai} semconv value — so the vendor identity is a field given at
 * construction rather than a constant, otherwise every xAI turn would be reported, and billed in a
 * dashboard, as an OpenAI one.
 *
 * <p>The xAI half of that is UNTESTED at present: it was pinned through {@code
 * XaiModelProviderBootstrap}, and ServiceLoader discovery has no counterpart in the new SPI. When a
 * discovery seam returns, so should a test that an xAI-built gateway reports {@code x_ai}.
 */
class OpenAiProviderNameTest {

  @Test
  void the_semconv_default_for_this_gateway_is_openai() {
    assertThat(OpenAiModelProvider.PROVIDER_NAME).isEqualTo("openai");
  }

  @Test
  void a_bound_model_answers_to_the_id_it_was_resolved_by() {
    ModelId id = ModelId.of("gpt-5");

    Model model = new OpenAiProviderConfig().apiKey("sk-test").build().model(id);

    assertThat(model.id()).isEqualTo(id);
  }
}
