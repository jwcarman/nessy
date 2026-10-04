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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The envelope a listener is handed")
class NarratedTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void a_story_event_without_a_position_is_refused() {
    Narration.Terminated event = new Narration.Terminated();
    Optional<Narrated.Position> none = Optional.empty();

    assertThatThrownBy(() -> new Narrated(TYPE, AGENT, event, none))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a story event has a position, and only a story event");
  }

  @Test
  void a_live_signal_with_a_position_is_refused() {
    Narration.Thinking event = new Narration.Thinking();
    Optional<Narrated.Position> position = Optional.of(new Narrated.Position(new Seq(1), AT));

    assertThatThrownBy(() -> new Narrated(TYPE, AGENT, event, position))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a story event has a position, and only a story event");
  }

  @Test
  void a_live_signal_made_with_the_factory_has_no_position() {
    Narrated narrated = Narrated.live(TYPE, AGENT, new Narration.Thinking());

    assertThat(narrated.position()).isEmpty();
    assertThat(narrated.event()).isEqualTo(new Narration.Thinking());
  }

  @Test
  void a_story_event_made_with_the_factory_has_the_seq_and_time_it_was_given() {
    Narrated narrated = Narrated.story(TYPE, AGENT, new Narration.Terminated(), new Seq(7), AT);

    assertThat(narrated.position()).contains(new Narrated.Position(new Seq(7), AT));
    assertThat(narrated.agentType()).isEqualTo(TYPE);
    assertThat(narrated.agentId()).isEqualTo(AGENT);
  }
}
