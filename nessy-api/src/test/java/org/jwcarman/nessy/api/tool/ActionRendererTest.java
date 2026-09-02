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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Saying what a call would do, in a sentence a person can read")
class ActionRendererTest {

  record RefundOrder(String orderId, int amountCents) {}

  @Test
  @DisplayName("byToString renders the input's own toString, which reads well for a record")
  void by_to_string_renders_the_inputs_own_string_form() {
    ActionRenderer<RefundOrder> renderer = ActionRenderer.byToString();
    RefundOrder input = new RefundOrder("ord_88", 4200);

    assertThat(renderer.render(input)).isEqualTo(input.toString());
  }
}
