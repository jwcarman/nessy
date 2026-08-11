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
package org.jwcarman.nessy.api.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TurnEventTest {

  @Test
  void aNoopObserverAcceptsEveryEventWithoutComplaint() {
    TurnObserver observer = TurnObserver.noop();
    observer.on(new TurnEvent.TextDelta("hello"));
    observer.on(new TurnEvent.ThinkingDelta("hmm"));
    observer.on(new TurnEvent.RedactedThinking("opaque"));
    assertThat(observer).isNotNull();
  }

  @Test
  void textDeltaRejectsNullText() {
    assertThatThrownBy(() -> new TurnEvent.TextDelta(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void thinkingDeltaRejectsNullText() {
    assertThatThrownBy(() -> new TurnEvent.ThinkingDelta(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void redactedThinkingRejectsNullData() {
    assertThatThrownBy(() -> new TurnEvent.RedactedThinking(null))
        .isInstanceOf(NullPointerException.class);
  }
}
