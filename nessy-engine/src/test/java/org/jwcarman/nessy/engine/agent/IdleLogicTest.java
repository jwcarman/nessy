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
package org.jwcarman.nessy.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("What an idle agent decides")
class IdleLogicTest {

  @Nested
  class HearingTheBacklogChanged {

    @Test
    void an_idle_agent_asks_for_work() {
      Decision decision = AgentLogic.decide(AgentState.idle(), new Input.BacklogUpdated());

      assertThat(decision.next().busy()).isFalse();
      assertThat(decision.then()).containsExactly(new Instruction.TakeWork());
    }

    @Test
    void a_busy_agent_ignores_it_because_going_idle_always_takes() {
      AgentState busy = AgentState.idle().taking("turn-1", "claim-1");

      Decision decision = AgentLogic.decide(busy, new Input.BacklogUpdated());

      assertThat(decision.next()).isEqualTo(busy);
      assertThat(decision.then()).isEmpty();
    }
  }

  @Nested
  class BeingHandedWork {

    @Test
    void taking_work_starts_a_turn_on_the_backlog_rows_own_id() {
      Decision decision =
          AgentLogic.decide(AgentState.idle(), new Input.WorkTaken("turn-7", "claim-7"));

      assertThat(decision.next().turnId()).isEqualTo("turn-7");
      assertThat(decision.next().observation()).isEqualTo("claim-7");
      assertThat(decision.next().phase()).isInstanceOf(Phase.CallingModel.class);
    }

    @Test
    void taking_work_announces_the_turn_before_it_calls_the_model() {
      Decision decision =
          AgentLogic.decide(AgentState.idle(), new Input.WorkTaken("turn-7", "claim-7"));

      assertThat(decision.then())
          .containsExactly(
              new Instruction.Narrate.TurnStarted("turn-7"),
              new Instruction.Remember.Input(),
              new Instruction.CallModel());
    }

    @Test
    void an_empty_backlog_puts_the_agent_to_sleep() {
      Decision decision = AgentLogic.decide(AgentState.idle(), new Input.NoWork());

      assertThat(decision.next().busy()).isFalse();
      assertThat(decision.then()).containsExactly(new Instruction.Sleep());
    }
  }
}
