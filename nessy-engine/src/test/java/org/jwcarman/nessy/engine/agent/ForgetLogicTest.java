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
import org.junit.jupiter.api.Test;

/**
 * Being told to disappear.
 *
 * <p>Cooperative, like an interrupt: the flag is set, and the agent acts on it at a moment of its
 * own choosing. The alternative — deleting an agent out from under a running turn — leaves the
 * model's answer arriving at a dead incarnation with nobody left to finish the turn, which is a
 * defect this engine has already had once and does not need under a new name.
 */
@DisplayName("Taking a poison pill")
class ForgetLogicTest {

  @Test
  @DisplayName("wipes the agent and then asks to be unloaded")
  void a_poisoned_take_forgets_and_sleeps() {
    Decision decision = AgentLogic.decide(AgentState.idle().asking(), new Input.Poisoned());

    assertThat(decision.then()).containsExactly(new Instruction.Forget(), new Instruction.Sleep());
  }

  @Test
  @DisplayName("ends the turn it was in, so nothing is left mid-flight")
  void a_poisoned_take_leaves_no_turn_running() {
    Decision decision = AgentLogic.decide(AgentState.idle().asking(), new Input.Poisoned());

    assertThat(decision.next().phase()).isInstanceOf(Phase.Idle.class);
    assertThat(decision.next().busy()).isFalse();
  }

  /**
   * The queued work dies with the agent rather than running on its way out.
   *
   * <p>The store enforces this — the pill is checked before any row is claimed — and this is the
   * half of it the logic owns: a poisoned take issues no TakeWork, so nothing goes looking again.
   */
  @Test
  void a_poisoned_take_does_not_ask_for_more_work() {
    Decision decision = AgentLogic.decide(AgentState.idle().asking(), new Input.Poisoned());

    assertThat(decision.then()).isNotEmpty();
    assertThat(decision.then()).doesNotContain(new Instruction.TakeWork());
  }
}
