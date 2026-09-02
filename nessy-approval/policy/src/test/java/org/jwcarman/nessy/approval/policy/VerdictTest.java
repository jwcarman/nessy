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
package org.jwcarman.nessy.approval.policy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a {@link Verdict} refuses to be, since a policy that could name an unnamed delegate would be
 * a gate an author could open by accident.
 */
@DisplayName("A verdict")
class VerdictTest {

  @Test
  @DisplayName("a delegate to a blank name is refused, since routing to nobody isn't routing")
  void a_delegate_with_a_blank_name_is_refused() {
    ObjectNode facts = JsonNodeFactory.instance.objectNode();

    assertThatThrownBy(() -> new Verdict.Delegate("   ", facts))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("a delegate must be named");
  }
}
