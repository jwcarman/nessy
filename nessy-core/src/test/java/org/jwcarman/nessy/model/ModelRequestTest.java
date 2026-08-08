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
package org.jwcarman.nessy.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.core.Message;

class ModelRequestTest {

  @Test
  void collectionsAreDefensivelyCopied() {
    List<Message> messages = new ArrayList<>();
    messages.add(Message.user("hi"));
    Set<Capability> requested = EnumSet.of(Capability.PROMPT_CACHING);

    ModelRequest request =
        new ModelRequest(messages, "be helpful", "some-model", 1024, List.of(), requested);

    messages.add(Message.user("sneaked in"));
    requested.add(Capability.THINKING);

    assertThat(request.messages()).hasSize(1);
    assertThat(request.requested()).containsExactly(Capability.PROMPT_CACHING);
  }

  @Test
  void unsupportedCapabilitiesAreVisibleRatherThanSilent() {
    ModelRequest request =
        new ModelRequest(
            List.of(Message.user("hi")),
            "be helpful",
            "some-model",
            1024,
            List.of(),
            Set.of(Capability.PROMPT_CACHING, Capability.THINKING));

    Set<Capability> unsupported = request.unsupportedBy(Set.of(Capability.THINKING));

    assertThat(unsupported).containsExactly(Capability.PROMPT_CACHING);
  }
}
