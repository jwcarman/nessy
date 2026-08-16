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
package org.jwcarman.nessy.api.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SubjectIdTest {

  @Test
  void a_blank_value_is_rejected() {
    assertThatThrownBy(() -> new SubjectId("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("value must not be blank");
  }

  @Test
  void a_null_value_is_rejected() {
    assertThatThrownBy(() -> new SubjectId(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("value must not be blank");
  }

  @Test
  void a_non_blank_value_is_kept_verbatim() {
    SubjectId subjectId = new SubjectId("user-42");

    assertThat(subjectId.value()).isEqualTo("user-42");
  }
}
