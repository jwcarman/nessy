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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.tool.ApprovalRequest;

@DisplayName("An agent's status as a value")
class AgentStatusTest {

  @Test
  void copies_the_list_of_waiting_approvals() {
    List<ApprovalRequest> given = new ArrayList<>();
    AgentStatus status = new AgentStatus(Activity.WAITING, 0, Optional.empty(), given, 0);

    given.add(null);

    assertThat(status.waitingApprovals()).isEmpty();
  }

  @Test
  void holds_its_waiting_approvals_unmodifiably() {
    AgentStatus status = new AgentStatus(Activity.IDLE, 0, Optional.empty(), List.of(), 0);
    List<ApprovalRequest> held = status.waitingApprovals();

    assertThatThrownBy(() -> held.add(null)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void refuses_no_activity() {
    assertThatThrownBy(() -> new AgentStatus(null, 0, Optional.empty(), List.of(), 0))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("activity");
  }

  @Test
  void refuses_no_turn() {
    assertThatThrownBy(() -> new AgentStatus(Activity.IDLE, 0, null, List.of(), 0))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("turn");
  }

  @Test
  void refuses_no_waiting_approvals() {
    assertThatThrownBy(() -> new AgentStatus(Activity.IDLE, 0, Optional.empty(), null, 0))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("waitingApprovals");
  }

  @Test
  void refuses_a_negative_queue() {
    assertThatThrownBy(() -> new AgentStatus(Activity.IDLE, -1, Optional.empty(), List.of(), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("queued");
  }

  @Test
  void refuses_a_negative_count_of_waiting_tool_calls() {
    assertThatThrownBy(() -> new AgentStatus(Activity.IDLE, 0, Optional.empty(), List.of(), -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("waitingToolCalls");
  }
}
