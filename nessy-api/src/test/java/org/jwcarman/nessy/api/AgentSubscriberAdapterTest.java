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

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The adapter's promise is that a watcher overriding nothing inherits silence for every variant —
 * including ones it never mentions, like {@link AgentEvent.TextDelta}, which no other test in this
 * module drives through a bare adapter.
 */
@DisplayName("An AgentSubscriber that unpacks narration into overridable hooks")
class AgentSubscriberAdapterTest {

  @Test
  @DisplayName("a variant nobody overrides falls through to its no-op hook silently")
  void an_unoverridden_variant_is_silently_ignored() {
    AgentSubscriberAdapter adapter = new AgentSubscriberAdapter() {};

    assertThatCode(() -> adapter.on(new AgentEvent.TextDelta("turn-1", "chunk")))
        .doesNotThrowAnyException();
  }
}
