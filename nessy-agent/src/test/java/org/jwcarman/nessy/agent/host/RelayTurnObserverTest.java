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
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.api.turn.TurnEvent;

class RelayTurnObserverTest {

  @Test
  void aSetObserverReceivesForwardedEvents() {
    var relay = new RelayTurnObserver();
    var observer = new RecordingTurnObserver();
    relay.set(observer);
    var event = new TurnEvent.TurnEnded(null);
    relay.on(event);
    assertThat(observer.events()).containsExactly(event);
  }

  @Test
  void aClearedRelayDropsEvents() {
    var relay = new RelayTurnObserver();
    var observer = new RecordingTurnObserver();
    relay.set(observer);
    relay.clear();
    relay.on(new TurnEvent.TurnEnded(null));
    assertThat(observer.events()).isEmpty();
  }

  @Test
  void settingANullObserverThrows() {
    var relay = new RelayTurnObserver();
    assertThatThrownBy(() -> relay.set(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void aSecondSetReplacesTheFirstObserver() {
    var relay = new RelayTurnObserver();
    var first = new RecordingTurnObserver();
    var second = new RecordingTurnObserver();
    relay.set(first);
    relay.set(second);
    var event = new TurnEvent.TurnEnded(null);
    relay.on(event);
    assertThat(first.events()).isEmpty();
    assertThat(second.events()).containsExactly(event);
  }
}
