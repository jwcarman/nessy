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

import org.junit.jupiter.api.Test;

class ConversationStateTest {

  private static final ConversationId ID = new ConversationId("s1");

  @Test
  void new_session_starts_empty_and_idle() {
    ConversationState state = ConversationState.newConversation(ID);

    assertThat(state.id()).isEqualTo(ID);
    assertThat(state.pendingCalls()).isEmpty();
    assertThat(state.pendingResults()).isEmpty();
    assertThat(state.consecutiveErrors()).isZero();
    assertThat(state.modelCalls()).isZero();
    assertThat(state.status()).isEqualTo(ConversationStatus.IDLE);
  }

  @Test
  void with_model_calls_returns_a_new_instance() {
    ConversationState original = ConversationState.newConversation(ID);

    ConversationState changed = original.withModelCalls(3);

    assertThat(changed.modelCalls()).isEqualTo(3);
    assertThat(original.modelCalls()).isZero();
  }

  @Test
  void withers_return_new_instances_and_leave_the_original_alone() {
    ConversationState original = ConversationState.newConversation(ID);

    ConversationState changed =
        original.with(ConversationStatus.AWAITING_MODEL).withConsecutiveErrors(2);

    assertThat(changed.status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
    assertThat(changed.consecutiveErrors()).isEqualTo(2);

    assertThat(original.status()).isEqualTo(ConversationStatus.IDLE);
    assertThat(original.consecutiveErrors()).isZero();
  }

  @Test
  void all_lists_are_unmodifiable() {
    ConversationState state = ConversationState.newConversation(ID);

    assertThat(state.pendingCalls()).isUnmodifiable();
    assertThat(state.pendingResults()).isUnmodifiable();
  }
}
