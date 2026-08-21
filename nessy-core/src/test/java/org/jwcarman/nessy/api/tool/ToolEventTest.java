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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ToolEventTest {

  @Test
  void aBlankProgressMessageIsRefused() {
    assertThatThrownBy(() -> new ToolEvent.Progress(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("message must not be blank");
  }

  @Test
  void aNullProgressMessageThrowsANullPointerExceptionLikeEverySiblingType() {
    assertThatThrownBy(() -> new ToolEvent.Progress(null)).isInstanceOf(NullPointerException.class);
  }
}
