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
package org.jwcarman.nessy.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.core.ParkToken;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.SessionState;
import org.jwcarman.nessy.core.SessionStatus;

class InMemorySessionStoreTest {

  private final SessionStore store = new InMemorySessionStore();
  private final SessionId id = new SessionId("s1");

  @Test
  void loadingAnUnknownSessionIsEmpty() {
    assertThat(store.load(id)).isEmpty();
  }

  @Test
  void savedStateComesBack() {
    SessionState state = SessionState.newSession(id).with(SessionStatus.COMPLETE);

    store.save(state);

    assertThat(store.load(id)).contains(state);
  }

  @Test
  void savingAgainReplaces() {
    store.save(SessionState.newSession(id).with(SessionStatus.AWAITING_MODEL));
    store.save(SessionState.newSession(id).with(SessionStatus.COMPLETE));

    assertThat(store.load(id).orElseThrow().status()).isEqualTo(SessionStatus.COMPLETE);
  }

  @Test
  void aTokenCanBeConsumedExactlyOnce() {
    ParkToken token = ParkToken.random();

    assertThat(store.consumeToken(token)).isTrue();
    assertThat(store.consumeToken(token)).isFalse();
  }
}
