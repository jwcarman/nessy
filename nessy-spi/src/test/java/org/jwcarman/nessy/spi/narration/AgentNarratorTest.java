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
package org.jwcarman.nessy.spi.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.inference.InferenceNarrator;

@DisplayName("Binding a narrator to an agent")
class AgentNarratorTest {

  @Test
  void a_narrator_bound_to_an_agent_tells_the_full_narrator_who() {
    AtomicReference<String> heard = new AtomicReference<>();
    Narrator narrator =
        (agentType, agentId, event) ->
            heard.set(agentType.value() + "/" + event.getClass().getSimpleName());
    AgentType chat = new AgentType("chat");

    narrator.forAgent(chat, new AgentId(UUID.randomUUID())).narrate(new Narration.Thinking());

    assertThat(heard).hasValue("chat/Thinking");
    assertThatCode(() -> AgentNarrator.silent().narrate(new Narration.Thinking()))
        .doesNotThrowAnyException();
  }

  /** A provider is handed text and thinking, and the engine is what turns them into events. */
  @Test
  void the_wire_view_of_a_narrator_mints_the_delta_events() {
    AtomicReference<Narration> heard = new AtomicReference<>();
    InferenceNarrator wire = ((AgentNarrator) heard::set).forInference();

    wire.text("hel");
    assertThat(heard).hasValue(new Narration.ContentDelta("hel"));

    wire.thinking("hmm");
    assertThat(heard).hasValue(new Narration.ThinkingDelta("hmm"));

    assertThatCode(() -> InferenceNarrator.silent().text("x")).doesNotThrowAnyException();
    assertThatCode(() -> InferenceNarrator.silent().thinking("x")).doesNotThrowAnyException();
  }
}
