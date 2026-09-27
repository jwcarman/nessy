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
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

  private final AgentId agent = AgentId.random();

  private static AgentEvent started(long seq, long turn) {
    return new AgentEvent.TurnStarted(new Seq(seq), new TurnId(turn), PayloadRef.of("p1"));
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
    events.append(TYPE, agent, List.of(started(1, 1), started(2, 2), started(3, 3)), Seq.NONE);

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

    events.append(support, shared, List.of(started(1, 1)), Seq.NONE);

    // Seq.NONE, not Seq(1): billing's stream is its own and has nothing in it yet.
    events.append(billing, shared, List.of(started(1, 1)), Seq.NONE);

    assertThat(events.readAll(support, shared)).hasSize(1);
    assertThat(events.readAll(billing, shared)).hasSize(1);
    assertThat(events.readAll(new AgentType("neither"), shared)).isEmpty();
  }

  @Test
  @DisplayName("an appended event's writtenAt is exactly what the clock said at append")
  void writtenAt_is_the_clock_reading_at_append() {
    Instant first = Instant.parse("2026-01-01T00:00:00Z");
    Instant second = Instant.parse("2026-01-01T00:05:00Z");
    Clock clock = new SteppedClock(first, second);
    AgentEvents events =
        new InMemoryAgentEvents(new JacksonCodecFactory(JsonMapper.builder().build()), clock);

    events.append(TYPE, agent, List.of(started(1, 1)), Seq.NONE);
    events.append(TYPE, agent, List.of(started(2, 2)), new Seq(1));

    assertThat(events.writtenAt(TYPE, agent, new Seq(1))).isEqualTo(first);
    assertThat(events.writtenAt(TYPE, agent, new Seq(2))).isEqualTo(second);
  }

  @Test
  @DisplayName("a default store stamps with the system clock, not with nothing")
  void the_no_arg_constructor_defaults_to_the_system_clock() {
    AgentEvents events =
        new InMemoryAgentEvents(new JacksonCodecFactory(JsonMapper.builder().build()));

    events.append(TYPE, agent, List.of(started(1, 1)), Seq.NONE);

    assertThat(events.writtenAt(TYPE, agent, new Seq(1)))
        .isCloseTo(Instant.now(), within(Duration.ofMillis(500)));
  }

  @Test
  @DisplayName("an unknown seq names the agent and the seq rather than staying quiet about it")
  void an_unknown_seq_throws() {
    AgentEvents events =
        new InMemoryAgentEvents(new JacksonCodecFactory(JsonMapper.builder().build()));
    events.append(TYPE, agent, List.of(started(1, 1)), Seq.NONE);
    Seq unknownSeq = new Seq(99);

    assertThatThrownBy(() -> events.writtenAt(TYPE, agent, unknownSeq))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("99")
        .hasMessageContaining(agent.value().toString());
  }

  /** A clock that hands out a fixed sequence of instants, one per call to {@link #instant()}. */
  private static final class SteppedClock extends Clock {
    private final Instant[] instants;
    private int next;

    SteppedClock(Instant... instants) {
      this.instants = instants;
    }

    @Override
    public Instant instant() {
      return instants[next++];
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException();
    }
  }
}
