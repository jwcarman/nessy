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
package org.jwcarman.nessy.agent.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.State;
import org.jwcarman.nessy.agent.codec.StateCodec;
import org.jwcarman.nessy.agent.support.MarkerBytesCodec;
import org.jwcarman.nessy.agent.support.TestClock;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.spi.substrate.Codec;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

class SubstrateAgentStateStoreTest {

  @Nested
  class RoundTripping {

    @Test
    void aFreshScopeLoadsTheInitialState() {
      var store =
          new SubstrateAgentStateStore(
              new InMemorySubstrate(), "agent-a", fixedClock(), TestMappers.plainlyPinned());
      assertThat(store.load()).isEqualTo(State.initial());
    }

    @Test
    void aSavedPhaseRoundTripsThroughTheKernel() {
      var store =
          new SubstrateAgentStateStore(
              new InMemorySubstrate(), "agent-a", fixedClock(), TestMappers.plainlyPinned());
      store.save(new State(new Phase.AwaitingModel(), store.load().version()));
      assertThat(store.load()).isEqualTo(new State(new Phase.AwaitingModel(), 1L));
    }

    @Test
    void repeatedSavesAdvanceTheVersionByExactlyOne() {
      var store =
          new SubstrateAgentStateStore(
              new InMemorySubstrate(), "agent-a", fixedClock(), TestMappers.plainlyPinned());
      store.save(new State(new Phase.AwaitingModel(), 0L));
      store.save(new State(new Phase.Idle(), 1L));
      assertThat(store.load()).isEqualTo(new State(new Phase.Idle(), 2L));
    }
  }

  @Nested
  class StaleSaves {

    @Test
    void aSaveAgainstAStaleVersionThrowsStaleStateException() {
      var store =
          new SubstrateAgentStateStore(
              new InMemorySubstrate(), "agent-a", fixedClock(), TestMappers.plainlyPinned());
      store.save(new State(new Phase.AwaitingModel(), 0L)); // stored version is now 1
      var stale = new State(new Phase.Idle(), 0L);

      assertThatThrownBy(() -> store.save(stale)).isInstanceOf(StaleStateException.class);
    }

    @Test
    void aStaleSaveCarriesBothTheExpectedAndTheActualVersion() {
      var store =
          new SubstrateAgentStateStore(
              new InMemorySubstrate(), "agent-a", fixedClock(), TestMappers.plainlyPinned());
      store.save(new State(new Phase.AwaitingModel(), 0L)); // stored version is now 1
      var stale = new State(new Phase.Idle(), 0L);

      var thrown = catchThrowableOfType(StaleStateException.class, () -> store.save(stale));

      assertThat(thrown.expected()).isZero();
      assertThat(thrown.found()).isEqualTo(1L);
    }
  }

  @Nested
  class LastSaved {

    @Test
    void aNeverSavedScopeReportsTheInstantTheStoreWasConstructed() {
      var birth = Instant.parse("2026-08-21T09:00:00Z");
      var store =
          new SubstrateAgentStateStore(
              new InMemorySubstrate(),
              "agent-a",
              new TestClock(birth),
              TestMappers.plainlyPinned());
      assertThat(store.lastSaved()).isEqualTo(birth);
    }

    @Test
    void aSaveReportsTheKernelsUpdatedAt() {
      var savedAt = Instant.parse("2026-08-21T09:05:00Z");
      var store =
          new SubstrateAgentStateStore(
              new InMemorySubstrate(new TestClock(savedAt)),
              "agent-a",
              fixedClock(),
              TestMappers.plainlyPinned());
      store.save(new State(new Phase.AwaitingModel(), 0L));
      assertThat(store.lastSaved()).isEqualTo(savedAt);
    }
  }

  @Nested
  class ACustomCodec {

    @Test
    void isHonoredByBothWritesAndReads() {
      Substrate substrate = new InMemorySubstrate();
      StateCodec stateCodec = new StateCodec(TestMappers.plainlyPinned());
      Codec<Phase> plain =
          new Codec<>() {
            @Override
            public byte[] encode(Phase phase) {
              return stateCodec.toJson(phase).getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public Phase decode(byte[] bytes) {
              return stateCodec.phase(new String(bytes, StandardCharsets.UTF_8));
            }
          };
      Codec<Phase> codec = plain.then(new MarkerBytesCodec());
      var store = new SubstrateAgentStateStore(substrate, "agent-a", fixedClock(), codec);

      store.save(new State(new Phase.AwaitingModel(), 0L));

      byte[] rawPayload = substrate.read("state", "agent-a").orElseThrow().payload();
      assertThat(MarkerBytesCodec.isMarked(rawPayload)).isTrue();
      assertThat(store.load()).isEqualTo(new State(new Phase.AwaitingModel(), 1L));
    }
  }

  private static TestClock fixedClock() {
    return new TestClock(Instant.parse("2026-08-21T08:00:00Z"));
  }
}
