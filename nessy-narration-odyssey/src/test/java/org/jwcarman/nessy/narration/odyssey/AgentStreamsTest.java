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
package org.jwcarman.nessy.narration.odyssey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.odyssey.core.TtlPolicy;

/** One stream per agent, and a resume that becomes a fresh subscription when there is no cursor. */
@DisplayName("Agent streams")
class AgentStreamsTest {

  private static final TtlPolicy A_DAY =
      new TtlPolicy(Duration.ofDays(1), Duration.ofDays(1), Duration.ofHours(1));
  private static final AgentType CHAT = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());

  private final AgentStreams streams = new AgentStreams(new RecordingOdyssey(), A_DAY);

  @Test
  void a_stream_is_named_by_type_and_id() {
    assertThat(AgentStreams.nameOf(CHAT, AGENT)).isEqualTo("nessy/chat/" + AGENT.value());
    assertThat(streams.stream(CHAT, AGENT).name()).isEqualTo("nessy/chat/" + AGENT.value());
  }

  @Test
  void subscribing_and_resuming_reach_the_stream() {
    // The recording Odyssey has no emitter to hand out; reaching it is what is being checked.
    assertThatThrownBy(() -> streams.subscribe(CHAT, AGENT))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> streams.resume(CHAT, AGENT, null))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> streams.resume(CHAT, AGENT, " "))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> streams.resume(CHAT, AGENT, "42"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
