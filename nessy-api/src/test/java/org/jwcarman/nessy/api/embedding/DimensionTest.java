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
package org.jwcarman.nessy.api.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DimensionTest {

  @Test
  void a_dimension_of_one_is_accepted_and_keeps_its_value() {
    assertThat(Dimension.of(1).value()).isEqualTo(1);
    assertThat(new Dimension(1024)).isEqualTo(Dimension.of(1024));
  }

  @Test
  void zero_is_refused() {
    assertThatThrownBy(() -> Dimension.of(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("an embedding dimension must be at least 1, was 0");
  }

  @Test
  void a_negative_width_is_refused() {
    assertThatThrownBy(() -> Dimension.of(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("an embedding dimension must be at least 1, was -1");
  }

  @Test
  void it_prints_as_the_number() {
    assertThat(Dimension.of(768)).hasToString("768");
  }
}
