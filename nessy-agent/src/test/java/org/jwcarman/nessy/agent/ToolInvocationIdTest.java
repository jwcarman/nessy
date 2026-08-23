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

class ToolInvocationIdTest {

  @Test
  void aToolInvocationIdCarriesTheResponseAndCallIdsAndRejectsBlankOrNullComponents() {
    var id = new ToolInvocationId("response-1", "call-1");
    assertThat(id.responseId()).isEqualTo("response-1");
    assertThat(id.callId()).isEqualTo("call-1");
    assertThatThrownBy(() -> new ToolInvocationId(null, "call-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ToolInvocationId(" ", "call-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ToolInvocationId("response-1", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ToolInvocationId("response-1", " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void equalToolInvocationIdsAreOneIdentity() {
    assertThat(new ToolInvocationId("response-1", "call-1"))
        .isEqualTo(new ToolInvocationId("response-1", "call-1"));
  }
}
