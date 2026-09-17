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
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
 * <p>The xAI half of that is UNTESTED at present: it is pinned through {@code
 * OpenAiAutoConfiguration#xaiModelProvider}, exercised by {@code OpenAiAutoConfigurationTest}'s
 * bean-wiring assertions, but nothing there makes a call and inspects what got reported. When
 * something does, so should a test that an xAI-built gateway reports {@code x_ai}.
 */
class OpenAiProviderNameTest {

  @Test
  void the_semconv_default_for_this_gateway_is_openai() {
    assertThat(OpenAiInferenceProvider.PROVIDER_NAME).isEqualTo("openai");
  }

  /**
   * There is no model handle to ask any more: which model to call travels in {@code
   * InferenceOptions}, so one provider serves every agent type. What is still worth pinning is that
   * the vendor a call is reported under is the one the gateway was built for.
   */
  @Test
  void a_gateway_pointed_at_xai_reports_that_vendor_rather_than_openai() {
    OpenAiInferenceProvider provider =
        OpenAiInferenceProvider.create(
            c -> c.apiKey("sk-test").baseUrl("https://api.x.ai/v1").provider("x_ai"));

    assertThat(provider.providerName()).isEqualTo("x_ai");
  }

  @Test
  void and_any_other_compatible_endpoint_still_answers_openai() {
    OpenAiInferenceProvider provider =
        OpenAiInferenceProvider.create(
            c -> c.apiKey("sk-test").baseUrl("https://openrouter.ai/api/v1"));

    assertThat(provider.providerName()).isEqualTo("openai");
  }
}
