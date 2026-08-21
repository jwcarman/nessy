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
package org.jwcarman.nessy.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Message;

class InMemoryMemorySubstrateTest {

  @Test
  void aFreshScopeRecallsAnEmptyContext() {
    var substrate = new InMemoryMemorySubstrate();
    assertThat(substrate.forScope("scope-a").recall().messages()).isEmpty();
  }

  @Test
  void rememberedMessagesRecallInOrder() {
    var substrate = new InMemoryMemorySubstrate();
    var view = substrate.forScope("scope-a");
    view.remember(Message.user("first"));
    view.remember(Message.user("second"));
    assertThat(view.recall().messages())
        .containsExactly(Message.user("first"), Message.user("second"));
  }

  @Test
  void recallReturnsASnapshotNotALiveView() {
    var substrate = new InMemoryMemorySubstrate();
    var view = substrate.forScope("scope-a");
    view.remember(Message.user("one"));
    List<Message> snapshot = view.recall().messages();
    view.remember(Message.user("two"));
    assertThat(snapshot).hasSize(1);
  }

  @Test
  void twoViewsOfTheSameIdShareRememberedMessages() {
    var substrate = new InMemoryMemorySubstrate();
    var first = substrate.forScope("scope-a");
    var second = substrate.forScope("scope-a");
    first.remember(Message.user("hello"));
    assertThat(second.recall().messages()).containsExactly(Message.user("hello"));
  }

  @Test
  void viewsOfDifferentIdsAreIsolated() {
    var substrate = new InMemoryMemorySubstrate();
    var scopeA = substrate.forScope("scope-a");
    var scopeB = substrate.forScope("scope-b");
    scopeA.remember(Message.user("for a"));
    assertThat(scopeB.recall().messages()).isEmpty();
    assertThat(scopeA.recall().messages()).containsExactly(Message.user("for a"));
  }
}
