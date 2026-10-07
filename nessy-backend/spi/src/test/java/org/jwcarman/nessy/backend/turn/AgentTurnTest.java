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
package org.jwcarman.nessy.backend.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnOutcome;

@DisplayName("A completed turn's row")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AgentTurnTest {

  private static final Instant NOW = Instant.parse("2026-10-07T14:02:01.220Z");
  private static final Trajectory TRAJECTORY = new Trajectory((short) 1, "8f".repeat(32));

  private static AgentTurn withToolCounts(int calls, int successes, int failures, int denials) {
    return new AgentTurn(
        new TurnId(1),
        new Seq(14),
        NOW,
        NOW,
        NOW,
        TRAJECTORY,
        "{\"rounds\":[],\"outcome\":\"ANSWERED\"}",
        TurnOutcome.ANSWERED,
        2,
        calls,
        successes,
        failures,
        denials,
        3,
        0);
  }

  @Test
  void a_row_whose_tool_counts_add_up_is_accepted() {
    AgentTurn row = withToolCounts(4, 2, 1, 1);
    assertThat(row.toolCalls()).isEqualTo(4);
  }

  @Test
  void a_row_whose_tool_calls_differ_from_the_sum_of_outcomes_is_refused() {
    int calls = 5;
    assertThatThrownBy(() -> withToolCounts(calls, 2, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
