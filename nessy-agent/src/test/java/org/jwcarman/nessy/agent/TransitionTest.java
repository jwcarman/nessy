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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Message;

class TransitionTest {

  @Test
  void aTransitionCarriesItsThreeDecisions() {
    var t =
        Transition.to(new Phase.AwaitingModel(), new Effect.CallModel()).commit(Message.user("hi"));
    assertThat(t.next()).isEqualTo(new Phase.AwaitingModel());
    assertThat(t.commit()).containsExactly(Message.user("hi"));
    assertThat(t.effects()).containsExactly(new Effect.CallModel());
    assertThat(t.isIgnored()).isFalse();
  }

  @Test
  void aBareTransitionCommitsNothingAndFiresNothing() {
    var t = Transition.to(new Phase.Idle());
    assertThat(t.commit()).isEmpty();
    assertThat(t.effects()).isEmpty();
  }

  @Test
  void emitAppendsEffectsInOrder() {
    var a = new Effect.CallModel();
    var t = Transition.to(new Phase.AwaitingModel()).emit(List.of(a));
    assertThat(t.effects()).containsExactly(a);
  }

  @Test
  void commitAppendsMessagesInOrder() {
    var t =
        Transition.to(new Phase.Idle())
            .commit(Message.user("first"))
            .commit(Message.user("second"));
    assertThat(t.commit()).containsExactly(Message.user("first"), Message.user("second"));
  }

  @Test
  void anIgnoredTransitionSaysSo() {
    assertThat(Transition.ignore().isIgnored()).isTrue();
  }

  @Test
  void anIgnoredTransitionHasNoNextPhase() {
    var ignored = Transition.ignore();
    assertThatThrownBy(ignored::next).isInstanceOf(IllegalStateException.class);
  }
}
