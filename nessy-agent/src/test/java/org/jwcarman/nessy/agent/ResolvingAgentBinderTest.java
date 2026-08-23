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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResolvingAgentBinderTest {

  /**
   * Fix round 1 M6: a resolver that hands back {@code null} must fail loudly through the same "not
   * a DefaultAgent" message every other unexpected {@link Agent} implementation does, not NPE out
   * of {@code Class#getName()} while building that message.
   */
  @Test
  void aNullResolveFailsLoudlyNamingNullRatherThanThrowingWhileBuildingTheMessage() {
    var binder = new ResolvingAgentBinder((type, id) -> null);
    var type = AgentType.of("test");
    var id = AgentId.of("scope-1");
    var event = new AgentEvent.Observed(List.of());

    assertThatThrownBy(() -> binder.deliver(type, id, event))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("resolved agent is not a DefaultAgent: null");
  }
}
