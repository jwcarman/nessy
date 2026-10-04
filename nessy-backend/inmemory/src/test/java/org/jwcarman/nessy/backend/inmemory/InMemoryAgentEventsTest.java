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

package org.jwcarman.nessy.backend.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("An agent's events, held only for as long as the process lives")
class InMemoryAgentEventsTest {

  private static final AgentType TYPE = new AgentType("chat");

  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

  private final AgentId agent = AgentId.random();

  private static AgentEvent started(long seq, long turn) {
    return new AgentEvent.TurnStarted(
        new Seq(seq), new TurnId(turn), PayloadRef.of("p1"), Instant.EPOCH);
  }

  /**
   * <b>Streaming is lazy, and this counts the proof.</b> The point of {@code streamFrom} is not
   * having a {@code Stream} in the signature -- it is that a caller who wants one event does not
   * pay for the whole story. Decoding is the expensive part, so decoding is what is counted: a
   * stream stopped after one element decodes one event, while the list form decodes all three.
   */
  @Test
  @DisplayName("a stream stopped early decodes only what was looked at")
  void streaming_does_not_decode_what_nobody_read() {
    AtomicInteger decoded = new AtomicInteger();
    AgentEvents events = new InMemoryAgentEvents(counting(decoded));
    events.append(TYPE, agent, List.of(started(1, 1), started(2, 2), started(3, 3)), Seq.NONE, AT);

    decoded.set(0);
    try (Stream<AgentEvent> stream = events.streamAll(TYPE, agent)) {
      assertThat(stream.findFirst()).isPresent();
    }
    assertThat(decoded.get()).isEqualTo(1);

    decoded.set(0);
    assertThat(events.readAll(TYPE, agent)).hasSize(3);
    assertThat(decoded.get()).isEqualTo(3);
  }

  /** Jackson, with a tally of how many events were turned back into objects. */
  private static CodecFactory counting(AtomicInteger decoded) {
    CodecFactory jackson = new JacksonCodecFactory(JsonMapper.builder().build());
    return new CodecFactory() {
      @Override
      public <T> Codec<T> create(TypeRef<T> type) {
        Codec<T> delegate = jackson.create(type);
        return new Codec<>() {
          @Override
          public byte[] encode(T value) {
            return delegate.encode(value);
          }

          @Override
          public T decode(byte[] bytes) {
            decoded.incrementAndGet();
            return delegate.decode(bytes);
          }
        };
      }
    };
  }

  /**
   * <b>An agent is a type and an id, and this is the assertion that says so.</b> A caller names its
   * own agent id, so an application keying agents off a business identifier -- one id per customer,
   * with a support agent and a billing agent over it -- gets two agent types on one id. Keyed by id
   * alone, those two shared a single story: each would replay the other's turns as its own, and the
   * fold would be deciding from a history that never happened.
   */
  @Test
  @DisplayName("two agent types sharing one id keep separate stories")
  void an_id_is_not_an_agent_on_its_own() {
    AgentEvents events =
        new InMemoryAgentEvents(new JacksonCodecFactory(JsonMapper.builder().build()));
    AgentType support = new AgentType("support");
    AgentType billing = new AgentType("billing");
    AgentId shared = AgentId.random();

    events.append(support, shared, List.of(started(1, 1)), Seq.NONE, AT);

    // Seq.NONE, not Seq(1): billing's stream is its own and has nothing in it yet.
    events.append(billing, shared, List.of(started(1, 1)), Seq.NONE, AT);

    assertThat(events.readAll(support, shared)).hasSize(1);
    assertThat(events.readAll(billing, shared)).hasSize(1);
    assertThat(events.readAll(new AgentType("neither"), shared)).isEmpty();
  }

  @Test
  @DisplayName("an appended event's writtenAt is exactly the instant it was appended with")
  void writtenAt_is_the_instant_given_at_append() {
    Instant first = Instant.parse("2026-01-01T00:00:00Z");
    Instant second = Instant.parse("2026-01-01T00:05:00Z");
    AgentEvents events =
        new InMemoryAgentEvents(new JacksonCodecFactory(JsonMapper.builder().build()));

    events.append(TYPE, agent, List.of(started(1, 1)), Seq.NONE, first);
    events.append(TYPE, agent, List.of(started(2, 2)), new Seq(1), second);

    assertThat(events.writtenAt(TYPE, agent, new Seq(1))).isEqualTo(first);
    assertThat(events.writtenAt(TYPE, agent, new Seq(2))).isEqualTo(second);
  }

  @Test
  @DisplayName("an unknown seq names the agent and the seq rather than staying quiet about it")
  void an_unknown_seq_throws() {
    AgentEvents events =
        new InMemoryAgentEvents(new JacksonCodecFactory(JsonMapper.builder().build()));
    events.append(TYPE, agent, List.of(started(1, 1)), Seq.NONE, AT);
    Seq unknownSeq = new Seq(99);

    assertThatThrownBy(() -> events.writtenAt(TYPE, agent, unknownSeq))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("99")
        .hasMessageContaining(agent.value().toString());
  }

  @Test
  @DisplayName("each event after the watermark comes back with the instant its batch was written")
  void streamWrittenFrom_pairs_each_event_with_its_instant() {
    Instant first = Instant.parse("2026-01-01T00:00:00Z");
    Instant second = Instant.parse("2026-01-01T00:05:00Z");
    AgentEvents events =
        new InMemoryAgentEvents(new JacksonCodecFactory(JsonMapper.builder().build()));
    events.append(TYPE, agent, List.of(started(1, 1), started(2, 2)), Seq.NONE, first);
    events.append(TYPE, agent, List.of(started(3, 3)), new Seq(2), second);

    List<AgentEvents.Written> written;
    try (Stream<AgentEvents.Written> stream = events.streamWrittenFrom(TYPE, agent, new Seq(1))) {
      written = stream.toList();
    }

    assertThat(written)
        .containsExactly(
            new AgentEvents.Written(started(2, 2), first),
            new AgentEvents.Written(started(3, 3), second));
  }
}
