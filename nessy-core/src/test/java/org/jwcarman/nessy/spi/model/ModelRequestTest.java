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
package org.jwcarman.nessy.spi.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;

class ModelRequestTest {

  @Test
  void the_requested_capability_set_is_defensively_copied() {
    Set<Capability> requested = EnumSet.of(Capability.PROMPT_CACHING);

    ModelRequest request =
        new ModelRequest(
            Context.of(List.of(Message.user("hi"))),
            "be helpful",
            "some-model",
            1024,
            List.of(),
            requested,
            null);

    requested.add(Capability.THINKING);

    assertThat(request.requested()).containsExactly(Capability.PROMPT_CACHING);
  }

  @Test
  void unsupported_capabilities_are_visible_rather_than_silent() {
    ModelRequest request =
        new ModelRequest(
            Context.of(List.of(Message.user("hi"))),
            "be helpful",
            "some-model",
            1024,
            List.of(),
            Set.of(Capability.PROMPT_CACHING, Capability.THINKING),
            null);

    Set<Capability> unsupported = request.unsupportedBy(Set.of(Capability.THINKING));

    assertThat(unsupported).containsExactly(Capability.PROMPT_CACHING);
  }
}
