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
package org.jwcarman.nessy.spi.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Something a provider may be able to do, that a caller may want used — asked for, never assumed.
 * The whole point of this type is that a {@link ModelRequest} can name one of these without knowing
 * which vendor will honor it, so what matters is that the exact five stay stable and nameable:
 * renaming or dropping one silently would break every adapter's {@code switch} over this type
 * without a compile error, since a caller only ever asks, never requires.
 */
@DisplayName("What a caller may ask a provider for")
class CapabilityTest {

  @Test
  @DisplayName("the five capabilities the adapters recognize, and no others")
  void exactly_the_five_named_capabilities_exist() {
    assertThat(Capability.values())
        .containsExactlyInAnyOrder(
            Capability.THINKING,
            Capability.PROMPT_CACHING,
            Capability.PROMPT_CACHING_1H,
            Capability.PARALLEL_TOOL_CALLS,
            Capability.IMAGE_INPUT);
  }

  @Test
  @DisplayName("a capability round-trips through its own name")
  void a_capability_round_trips_through_its_name() {
    for (Capability capability : Capability.values()) {
      assertThat(Capability.valueOf(capability.name())).isEqualTo(capability);
    }
  }
}
