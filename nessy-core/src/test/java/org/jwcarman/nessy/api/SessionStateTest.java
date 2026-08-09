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

import java.util.List;
import org.junit.jupiter.api.Test;

class SessionStateTest {

  private static final SessionId ID = new SessionId("s1");

  @Test
  void newSessionStartsEmptyAndIdle() {
    SessionState state = SessionState.newSession(ID);

    assertThat(state.id()).isEqualTo(ID);
    assertThat(state.messages()).isEmpty();
    assertThat(state.pendingBlocks()).isEmpty();
    assertThat(state.pendingCalls()).isEmpty();
    assertThat(state.pendingResults()).isEmpty();
    assertThat(state.consecutiveErrors()).isZero();
    assertThat(state.status()).isEqualTo(SessionStatus.IDLE);
  }

  @Test
  void withersReturnNewInstancesAndLeaveTheOriginalAlone() {
    SessionState original = SessionState.newSession(ID);

    SessionState changed =
        original
            .withMessageAppended(Message.user("hi"))
            .with(SessionStatus.AWAITING_MODEL)
            .withConsecutiveErrors(2);

    assertThat(changed.messages()).hasSize(1);
    assertThat(changed.status()).isEqualTo(SessionStatus.AWAITING_MODEL);
    assertThat(changed.consecutiveErrors()).isEqualTo(2);

    assertThat(original.messages()).isEmpty();
    assertThat(original.status()).isEqualTo(SessionStatus.IDLE);
    assertThat(original.consecutiveErrors()).isZero();
  }

  @Test
  void allListsAreUnmodifiable() {
    SessionState state = SessionState.newSession(ID);

    assertThat(state.messages()).isUnmodifiable();
    assertThat(state.pendingBlocks()).isUnmodifiable();
    assertThat(state.pendingCalls()).isUnmodifiable();
    assertThat(state.pendingResults()).isUnmodifiable();
  }

  @Test
  void withPendingBlocksReplacesRatherThanAppends() {
    SessionState state =
        SessionState.newSession(ID)
            .withPendingBlocks(List.of(new TextBlock("a")))
            .withPendingBlocks(List.of(new TextBlock("b")));

    assertThat(state.pendingBlocks()).containsExactly(new TextBlock("b"));
  }
}
