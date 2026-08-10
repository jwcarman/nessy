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

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;

class ConversationStateTest {

  private static final ConversationId ID = new ConversationId("s1");

  @Test
  void new_session_starts_empty_and_idle() {
    ConversationState state = ConversationState.newConversation(ID);

    assertThat(state.id()).isEqualTo(ID);
    assertThat(state.messages()).isEmpty();
    assertThat(state.pendingBlocks()).isEmpty();
    assertThat(state.pendingCalls()).isEmpty();
    assertThat(state.pendingResults()).isEmpty();
    assertThat(state.consecutiveErrors()).isZero();
    assertThat(state.lastInputTokens()).isZero();
    assertThat(state.generation()).isZero();
    assertThat(state.status()).isEqualTo(ConversationStatus.IDLE);
  }

  @Test
  void with_last_input_tokens_returns_a_new_instance() {
    ConversationState original = ConversationState.newConversation(ID);

    ConversationState changed = original.withLastInputTokens(42);

    assertThat(changed.lastInputTokens()).isEqualTo(42);
    assertThat(original.lastInputTokens()).isZero();
  }

  @Test
  void with_generation_returns_a_new_instance() {
    ConversationState original = ConversationState.newConversation(ID);

    ConversationState changed = original.withGeneration(3);

    assertThat(changed.generation()).isEqualTo(3);
    assertThat(original.generation()).isZero();
  }

  @Test
  void a_negative_last_input_tokens_is_rejected() {
    ConversationState state = ConversationState.newConversation(ID);

    assertThatThrownBy(() -> state.withLastInputTokens(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_negative_generation_is_rejected() {
    ConversationState state = ConversationState.newConversation(ID);

    assertThatThrownBy(() -> state.withGeneration(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withers_return_new_instances_and_leave_the_original_alone() {
    ConversationState original = ConversationState.newConversation(ID);

    ConversationState changed =
        original
            .withMessageAppended(Message.user("hi"))
            .with(ConversationStatus.AWAITING_MODEL)
            .withConsecutiveErrors(2);

    assertThat(changed.messages()).hasSize(1);
    assertThat(changed.status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
    assertThat(changed.consecutiveErrors()).isEqualTo(2);

    assertThat(original.messages()).isEmpty();
    assertThat(original.status()).isEqualTo(ConversationStatus.IDLE);
    assertThat(original.consecutiveErrors()).isZero();
  }

  @Test
  void all_lists_are_unmodifiable() {
    ConversationState state = ConversationState.newConversation(ID);

    assertThat(state.messages()).isUnmodifiable();
    assertThat(state.pendingBlocks()).isUnmodifiable();
    assertThat(state.pendingCalls()).isUnmodifiable();
    assertThat(state.pendingResults()).isUnmodifiable();
  }

  @Test
  void with_pending_blocks_replaces_rather_than_appends() {
    ConversationState state =
        ConversationState.newConversation(ID)
            .withPendingBlocks(List.of(new TextBlock("a")))
            .withPendingBlocks(List.of(new TextBlock("b")));

    assertThat(state.pendingBlocks()).containsExactly(new TextBlock("b"));
  }
}
