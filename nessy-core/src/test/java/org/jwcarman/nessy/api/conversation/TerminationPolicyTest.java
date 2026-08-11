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
package org.jwcarman.nessy.api.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TerminationPolicyTest {

  private static ConversationState stateWith(int modelCalls, int consecutiveErrors) {
    return ConversationState.newConversation(new ConversationId("s1"))
        .withModelCalls(modelCalls)
        .withConsecutiveErrors(consecutiveErrors);
  }

  @Test
  void max_model_calls_halts_at_the_ceiling_and_not_below() {
    TerminationPolicy policy = TerminationPolicy.maxModelCalls(5);

    assertThat(policy.shouldHalt(stateWith(4, 0))).isEmpty();
    assertThat(policy.shouldHalt(stateWith(5, 0))).isPresent();
  }

  @Test
  void max_consecutive_errors_halts_at_the_ceiling_and_not_below() {
    TerminationPolicy policy = TerminationPolicy.maxConsecutiveErrors(2);

    assertThat(policy.shouldHalt(stateWith(0, 1))).isEmpty();
    assertThat(policy.shouldHalt(stateWith(0, 2))).isPresent();
  }

  @Test
  void any_of_reports_the_first_halting_policy() {
    TerminationPolicy policy =
        TerminationPolicy.anyOf(
            TerminationPolicy.maxConsecutiveErrors(2), TerminationPolicy.maxModelCalls(5));

    assertThat(policy.shouldHalt(stateWith(9, 0)).orElseThrow()).contains("model calls");
    assertThat(policy.shouldHalt(stateWith(0, 9)).orElseThrow()).contains("consecutive");
  }

  @Test
  void never_never_halts() {
    assertThat(TerminationPolicy.never().shouldHalt(stateWith(1_000_000, 1_000_000))).isEmpty();
  }

  @Test
  void ceilings_below_one_are_rejected() {
    assertThatThrownBy(() -> TerminationPolicy.maxModelCalls(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TerminationPolicy.maxConsecutiveErrors(0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
