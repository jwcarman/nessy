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
package org.jwcarman.nessy.agent.intent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.intent.Intent;

class InMemoryIntentStoreTest {

  @Test
  void anUnwrittenStoreHoldsNoDeclaration() {
    var store = new InMemoryIntentStore<Intent>();

    assertThat(store.latest()).isEmpty();
  }

  @Test
  void aRecordedDeclarationIsRecoverable() {
    var store = new InMemoryIntentStore<Intent>();

    store.declare(new Intent("restart prod-eu to clear the stuck deploy"));

    assertThat(store.latest()).contains(new Intent("restart prod-eu to clear the stuck deploy"));
  }

  @Test
  void aSecondRecordingReplacesTheFirstLastWriteWins() {
    var store = new InMemoryIntentStore<Intent>();

    store.declare(new Intent("first declaration"));
    store.declare(new Intent("second declaration"));

    assertThat(store.latest()).contains(new Intent("second declaration"));
  }
}
