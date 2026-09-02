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
package org.jwcarman.nessy.api.block;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code ProviderBlock} carries a vendor's opaque state — Anthropic's cache signature, Gemini's
 * thought signature — untouched, so it needs to know only which vendor it came from and that
 * something is actually there to carry.
 */
@DisplayName("A provider's opaque state, carried without being understood")
class ProviderBlockTest {

  @Test
  @DisplayName("names the provider whose state it is, and carries that state")
  void carries_provider_and_data() {
    ObjectNode data = JsonNodeFactory.instance.objectNode().put("signature", "abc");

    ProviderBlock block = new ProviderBlock("anthropic", data);

    assertThat(block.provider()).isEqualTo("anthropic");
    assertThat(block.data()).isEqualTo(data);
  }

  @Test
  @DisplayName("refuses a blank provider name, since a transcript outlives the model choice")
  void refuses_a_blank_provider() {
    ObjectNode data = JsonNodeFactory.instance.objectNode();

    assertThatThrownBy(() -> new ProviderBlock(" ", data))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }

  @Test
  @DisplayName("refuses a null data node rather than silently carrying nothing")
  void refuses_null_data() {
    assertThatThrownBy(() -> new ProviderBlock("anthropic", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("data must not be null");
  }
}
