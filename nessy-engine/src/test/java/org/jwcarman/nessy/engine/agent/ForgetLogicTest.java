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
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;

/**
 * Being told to disappear.
 *
 * <p>Cooperative, like an interrupt: the flag is set, and the agent acts on it at a moment of its
 * own choosing. The alternative — deleting an agent out from under a running turn — leaves the
 * model's answer arriving at a dead incarnation with nobody left to finish the turn, which is a
 * defect this engine has already had once and does not need under a new name.
 */
@DisplayName("An agent told to forget itself")
class ForgetLogicTest {

  private static AgentState busy() {
    return AgentState.idle().taking(TurnId.of("turn-1"), "claim-1");
  }

  @Nested
  @DisplayName("when it is idle")
  class Idle {

    @Test
    void it_forgets_itself_at_once() {
      Decision decision = AgentLogic.decide(AgentState.idle(), new Input.Forget());

      assertThat(decision.then()).contains(new Instruction.Forget());
      assertThat(decision.next().forgetting()).isTrue();
    }

    @Test
    @DisplayName("being told twice is the same as being told once")
    void it_is_idempotent() {
      Decision once = AgentLogic.decide(AgentState.idle(), new Input.Forget());
      Decision twice = AgentLogic.decide(once.next(), new Input.Forget());

      assertThat(twice.then()).contains(new Instruction.Forget());
      assertThat(twice.next().forgetting()).isTrue();
    }
  }

  @Nested
  @DisplayName("when it is busy")
  class Busy {

    @Test
    @DisplayName("nothing is deleted while a turn is running")
    void it_only_records_the_flag() {
      Decision decision = AgentLogic.decide(busy(), new Input.Forget());

      assertThat(decision.next().forgetting()).isTrue();
      assertThat(decision.then())
          .as("deleting under a running turn strands its answer in a dead incarnation")
          .doesNotContain(new Instruction.Forget());
    }

    @Test
    @DisplayName("the flag survives the turn, because it lives in persisted state")
    void the_flag_is_carried_through_the_turn() {
      AgentState told = AgentLogic.decide(busy(), new Input.Forget()).next();

      // Every transition a turn makes must carry it, or a restart would resurrect the agent.
      assertThat(told.at(new Phase.CallingModel()).forgetting()).isTrue();
      assertThat(told.spending(new Usage(1, 1)).forgetting()).isTrue();
      assertThat(told.finished().forgetting()).isTrue();
    }

    @Test
    @DisplayName("the turn ends, and THEN it forgets itself instead of taking more work")
    void it_forgets_when_the_turn_ends() {
      AgentState told = AgentLogic.decide(busy(), new Input.Forget()).next();

      Decision ending =
          AgentLogic.decide(
              told, new Input.ModelAnswered.Answered(StopReason.END_TURN, Usage.unreported()));

      assertThat(ending.then()).contains(new Instruction.Forget());
      assertThat(ending.then())
          .as("an agent on its way out does not start something new")
          .doesNotContain(new Instruction.TakeWork());
    }
  }

  @Nested
  @DisplayName("once it is on its way out")
  class OnItsWayOut {

    @Test
    @DisplayName("new work offered is not picked up")
    void it_ignores_the_backlog() {
      AgentState told = AgentLogic.decide(AgentState.idle(), new Input.Forget()).next();

      Decision decision = AgentLogic.decide(told, new Input.BacklogUpdated());

      assertThat(decision.then()).doesNotContain(new Instruction.TakeWork());
    }

    @Test
    @DisplayName("an agent that was NOT told still takes work, which is the control")
    void an_ordinary_agent_still_takes_work() {
      Decision decision = AgentLogic.decide(AgentState.idle(), new Input.BacklogUpdated());

      assertThat(decision.then()).contains(new Instruction.TakeWork());
    }
  }
}
