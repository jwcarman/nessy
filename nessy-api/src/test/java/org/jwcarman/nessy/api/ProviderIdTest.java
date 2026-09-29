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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProviderIdTest {

  @Test
  void a_provider_id_keeps_the_name_it_was_given() {
    assertThat(ProviderId.of("openai-batch").value()).isEqualTo("openai-batch");
  }

  @Test
  void two_ids_with_the_same_name_are_the_same_id() {
    assertThat(ProviderId.of("xai")).isEqualTo(new ProviderId("xai"));
  }

  @Test
  void a_blank_id_is_refused() {
    assertThatThrownBy(() -> ProviderId.of(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provider id");
  }

  @Test
  void an_id_longer_than_sixty_four_characters_is_refused() {
    String tooLong = "p".repeat(65);
    assertThatThrownBy(() -> ProviderId.of(tooLong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provider id");
  }
}
