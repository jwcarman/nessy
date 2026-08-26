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

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.Model;

/**
 * {@code gen_ai.provider.name} for this vendor (agentic-o11y spec §1.1). The value is one of the
 * OpenTelemetry GenAI semantic conventions' pinned strings and is read straight onto every {@code
 * chat} span, so it is a compatibility surface with whatever dashboard groups by vendor: pinned
 * here rather than left to a class-name default that would report {@code AnthropicModelProvider}.
 */
class AnthropicProviderNameTest {

  @Test
  void every_bound_model_reports_the_semconv_value_for_anthropic() {
    Model model = new AnthropicProviderConfig().apiKey("sk-test").build().model("claude-opus-5");

    assertThat(model.provider()).isEqualTo("anthropic");
    assertThat(AnthropicModelProvider.PROVIDER).isEqualTo("anthropic");
  }
}
