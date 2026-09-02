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
package org.jwcarman.nessy.api.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Where a deferred answer goes")
class ReplyTokenTest {

  @Test
  @DisplayName("of wraps the opaque value")
  void of_wraps_the_value() {
    ReplyToken token = ReplyToken.of("opaque-1");

    assertThat(token.value()).isEqualTo("opaque-1");
  }

  @Test
  @DisplayName("a blank token cannot address anything, so it is refused rather than minted")
  void refuses_a_blank_value() {
    assertThatThrownBy(() -> ReplyToken.of(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }
}
