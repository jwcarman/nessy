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
package org.jwcarman.nessy.agent.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IntentTest {

  @Test
  void a_declaration_is_recoverable_verbatim() {
    var intent = new Intent("restart prod-eu to clear the stuck deploy");

    assertThat(intent.declaration()).isEqualTo("restart prod-eu to clear the stuck deploy");
  }

  @Test
  void a_blank_declaration_is_rejected() {
    assertThatThrownBy(() -> new Intent("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("declaration");
  }

  @Test
  void a_null_declaration_is_rejected() {
    assertThatThrownBy(() -> new Intent(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("declaration");
  }
}
