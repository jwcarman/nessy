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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModelResponseIdTest {

  @Test
  void carriesItsValueAndRejectsNullOrBlank() {
    assertThat(ModelResponseId.of("response-1").value()).isEqualTo("response-1");
    assertThatThrownBy(() -> ModelResponseId.of(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ModelResponseId.of(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void generateMintsADistinctIdEveryTime() {
    var one = ModelResponseId.generate();
    var two = ModelResponseId.generate();
    assertThat(one.value()).isNotBlank();
    assertThat(two.value()).isNotBlank();
    assertThat(one).isNotEqualTo(two);
  }

  @Test
  void equalValuesAreOneIdentity() {
    assertThat(ModelResponseId.of("x")).isEqualTo(ModelResponseId.of("x"));
  }
}
