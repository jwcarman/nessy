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

import org.junit.jupiter.api.Test;

/**
 * {@code gen_ai.provider.name} for this vendor (agentic-o11y spec §1.1). The value is one of the
 * OpenTelemetry GenAI semantic conventions' pinned strings, so it is a compatibility surface with
 * whatever dashboard groups by vendor: pinned here rather than left to a class-name default that
 * would report {@code AnthropicInferenceProvider}.
 *
 * <p>A bound model no longer REPORTS it — {@code Model} describes nothing now, it only answers to
 * an id — so this pins the constant and the id a bound handle carries, which is what survived.
 */
class AnthropicProviderNameTest {

  @Test
  void the_semconv_value_for_this_vendor_is_pinned() {
    assertThat(AnthropicInferenceProvider.PROVIDER_NAME).isEqualTo("anthropic");
  }

  /**
   * There is no model handle to ask any more -- which model to call travels in {@code
   * InferenceOptions} -- so what is left worth pinning is the name itself.
   */
  @Test
  void the_gateway_answers_to_its_vendor() {
    assertThat(AnthropicInferenceProvider.create(c -> c.apiKey("sk-test")).name())
        .isEqualTo("Anthropic");
  }
}
