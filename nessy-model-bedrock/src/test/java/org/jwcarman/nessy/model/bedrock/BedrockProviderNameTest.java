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
package org.jwcarman.nessy.model.bedrock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.spi.model.Model;

/**
 * {@code gen_ai.provider.name} for this vendor (agentic-o11y spec §1.1) — {@code aws.bedrock}, the
 * hosting platform, not whichever vendor's weights a given Bedrock model id names. A Claude model
 * served through Bedrock is a Bedrock call: that is what the latency and the bill belong to.
 */
class BedrockProviderNameTest {

  @Test
  void the_semconv_value_for_this_vendor_is_pinned() {
    assertThat(BedrockModelProvider.PROVIDER).isEqualTo("aws.bedrock");
  }

  @Test
  void a_bound_model_answers_to_the_id_it_was_resolved_by() {
    ModelId id = ModelId.of("us.anthropic.claude-haiku-4-5-20251001-v1:0");

    Model model = new BedrockModelProvider(request -> null).model(id);

    assertThat(model.id()).isEqualTo(id);
  }
}
