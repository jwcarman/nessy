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
package org.jwcarman.nessy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;

/**
 * {@link Reply}, sugar over {@link ConversationState} — pure, so entirely testable off the wire.
 */
class ReplyTest {

  private static final ConversationId ID = new ConversationId("s1");

  @Test
  void a_null_outcome_is_rejected() {
    assertThatThrownBy(() -> new Reply(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void state_delegates_to_the_outcomes_own_state_for_both_variants() {
    ConversationState state = ConversationState.newConversation(ID);

    assertThat(new Reply(new RunOutcome.Completed(state)).state()).isEqualTo(state);
    assertThat(new Reply(new RunOutcome.Parked(state, ParkToken.generate())).state())
        .isEqualTo(state);
  }

  @Nested
  class Text {

    @Test
    void empty_when_there_is_no_assistant_message_at_all() {
      ConversationState state =
          ConversationState.newConversation(ID).withMessages(List.of(Message.user("hi")));

      assertThat(new Reply(new RunOutcome.Completed(state)).text()).isEmpty();
    }

    @Test
    void the_last_assistant_messages_prose_wins_when_there_is_more_than_one() {
      ConversationState state =
          ConversationState.newConversation(ID)
              .withMessages(
                  List.of(
                      Message.user("hi"),
                      Message.assistant(List.of(new TextBlock("first"))),
                      Message.user("and?"),
                      Message.assistant(List.of(new TextBlock("second")))));

      assertThat(new Reply(new RunOutcome.Completed(state)).text()).isEqualTo("second");
    }

    @Test
    void non_text_blocks_in_the_last_assistant_message_contribute_nothing() {
      ConversationState state =
          ConversationState.newConversation(ID).withMessages(List.of(Message.assistant(List.of())));

      assertThat(new Reply(new RunOutcome.Completed(state)).text()).isEmpty();
    }
  }

  @Nested
  class Failed_and_failureReason {

    @Test
    void failed_is_false_for_every_non_failed_status() {
      ConversationState state = ConversationState.newConversation(ID).with(ConversationStatus.IDLE);

      assertThat(new Reply(new RunOutcome.Completed(state)).failed()).isFalse();
    }

    @Test
    void failed_is_true_once_the_status_is_FAILED() {
      ConversationState state =
          ConversationState.newConversation(ID).with(ConversationStatus.FAILED);

      assertThat(new Reply(new RunOutcome.Completed(state)).failed()).isTrue();
    }

    @Test
    void failureReason_is_empty_when_none_was_recorded() {
      ConversationState state = ConversationState.newConversation(ID);

      assertThat(new Reply(new RunOutcome.Completed(state)).failureReason()).isEmpty();
    }

    @Test
    void failureReason_carries_the_recorded_reason() {
      ConversationState state =
          ConversationState.newConversation(ID)
              .with(ConversationStatus.FAILED)
              .withFailureReason("hit the error ceiling");

      assertThat(new Reply(new RunOutcome.Completed(state)).failureReason())
          .contains("hit the error ceiling");
    }
  }
}
