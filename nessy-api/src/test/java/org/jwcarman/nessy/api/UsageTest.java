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

class UsageTest {

  @Test
  void unknown_is_not_zero_and_a_half_known_count_is_refused() {
    assertThat(Usage.unknown().known()).isFalse();
    assertThat(Usage.unknown().totalTokens()).isEqualTo(-1);
    assertThat(new Usage(0, 0).known()).isTrue();
    assertThat(new Usage(3, 4).totalTokens()).isEqualTo(7);
    assertThatThrownBy(() -> new Usage(3, -1)).isInstanceOf(IllegalArgumentException.class);
  }
}
