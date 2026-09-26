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

package org.jwcarman.nessy.engine.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.engine.core.AgentEvent;
import org.jwcarman.nessy.engine.core.AgentEventStore;
import org.jwcarman.nessy.inference.Seq;
import org.jwcarman.nessy.inference.TurnId;

@DisplayName("An agent's events, held only for as long as the process lives")
class InMemoryAgentEventStoreTest {

  private final AgentId agent = AgentId.random();

  private static AgentEvent started(long seq, long turn) {
    return new AgentEvent.TurnStarted(new Seq(seq), new TurnId(turn), PayloadRef.of("p1"));
  }

  @Test
  @DisplayName("an appended event's writtenAt is exactly what the clock said at append")
  void writtenAt_is_the_clock_reading_at_append() {
    Instant first = Instant.parse("2026-01-01T00:00:00Z");
    Instant second = Instant.parse("2026-01-01T00:05:00Z");
    Clock clock = new SteppedClock(first, second);
    AgentEventStore events = new InMemoryAgentEventStore(clock);

    events.append(agent, List.of(started(1, 1)), Seq.NONE);
    events.append(agent, List.of(started(2, 2)), new Seq(1));

    assertThat(events.writtenAt(agent, new Seq(1))).isEqualTo(first);
    assertThat(events.writtenAt(agent, new Seq(2))).isEqualTo(second);
  }

  @Test
  @DisplayName("a default store stamps with the system clock, not with nothing")
  void the_no_arg_constructor_defaults_to_the_system_clock() {
    AgentEventStore events = new InMemoryAgentEventStore();

    events.append(agent, List.of(started(1, 1)), Seq.NONE);

    assertThat(events.writtenAt(agent, new Seq(1)))
        .isCloseTo(Instant.now(), within(Duration.ofMillis(500)));
  }

  @Test
  @DisplayName("an unknown seq names the agent and the seq rather than staying quiet about it")
  void an_unknown_seq_throws() {
    AgentEventStore events = new InMemoryAgentEventStore();
    events.append(agent, List.of(started(1, 1)), Seq.NONE);

    assertThatThrownBy(() -> events.writtenAt(agent, new Seq(99)))
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
