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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Binding a narrator to an agent")
class AgentNarratorTest {

  @Test
  void a_narrator_bound_to_an_agent_tells_the_full_narrator_who() {
    AtomicReference<String> heard = new AtomicReference<>();
    Narrator narrator =
        narrated ->
            heard.set(
                narrated.agentType().value() + "/" + narrated.event().getClass().getSimpleName());
    AgentType chat = new AgentType("chat");

    narrator.forAgent(chat, new AgentId(UUID.randomUUID())).narrate(new Narration.Thinking());

    assertThat(heard).hasValue("chat/Thinking");
    assertThatCode(() -> AgentNarrator.silent().narrate(new Narration.Thinking()))
        .doesNotThrowAnyException();
  }
}
