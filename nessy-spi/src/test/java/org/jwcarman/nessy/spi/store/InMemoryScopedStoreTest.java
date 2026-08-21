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
package org.jwcarman.nessy.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InMemoryScopedStoreTest {

  @Nested
  class Documents {

    @Test
    void writeAtVersionZeroCreatesTheDocumentAtVersionOne() {
      var store = new InMemoryScopedStore();
      store.write("state", "agent-a", "{\"phase\":\"idle\"}", 0L);
      assertThat(store.read("state", "agent-a"))
          .contains(
              new ScopedStore.Document(
                  "{\"phase\":\"idle\"}",
                  1L,
                  store.read("state", "agent-a").orElseThrow().updatedAt()));
    }

    @Test
    void aMatchingCasWriteIncrementsTheVersionByOne() {
      var store = new InMemoryScopedStore();
      store.write("state", "agent-a", "v1", 0L);
      store.write("state", "agent-a", "v2", 1L);
      assertThat(store.read("state", "agent-a").orElseThrow().version()).isEqualTo(2L);
      assertThat(store.read("state", "agent-a").orElseThrow().payload()).isEqualTo("v2");
    }

    @Test
    void aCreateWriteAgainstAnAlreadyPresentDocumentThrowsConflict() {
      var store = new InMemoryScopedStore();
      store.write("state", "agent-a", "v1", 0L);
      assertThatThrownBy(() -> store.write("state", "agent-a", "v2", 0L))
          .isInstanceOf(ConflictException.class);
    }

    @Test
    void aStaleCasWriteThrowsConflict() {
      var store = new InMemoryScopedStore();
      store.write("state", "agent-a", "v1", 0L);
      assertThatThrownBy(() -> store.write("state", "agent-a", "v2", 5L))
          .isInstanceOf(ConflictException.class);
    }

    @Test
    void aStaleDeleteThrowsConflict() {
      var store = new InMemoryScopedStore();
      store.write("state", "agent-a", "v1", 0L);
      assertThatThrownBy(() -> store.delete("state", "agent-a", 5L))
          .isInstanceOf(ConflictException.class);
    }

    @Test
    void aMatchingDeleteRemovesTheDocument() {
      var store = new InMemoryScopedStore();
      store.write("state", "agent-a", "v1", 0L);
      store.delete("state", "agent-a", 1L);
      assertThat(store.read("state", "agent-a")).isEmpty();
    }

    @Test
    void readingAnAbsentDocumentReturnsEmpty() {
      var store = new InMemoryScopedStore();
      assertThat(store.read("state", "unknown-agent")).isEmpty();
    }

    @Test
    void keysComeBackInAscendingLexicographicOrderUpToTheLimit() {
      var store = new InMemoryScopedStore();
      store.write("state", "charlie", "v", 0L);
      store.write("state", "alpha", "v", 0L);
      store.write("state", "bravo", "v", 0L);
      assertThat(store.keys("state", 2)).containsExactly("alpha", "bravo");
    }

    @Test
    void keysOnlyReportsTheRequestedKind() {
      var store = new InMemoryScopedStore();
      store.write("state", "agent-a", "v", 0L);
      store.write("memory", "agent-a", "v", 0L);
      assertThat(store.keys("state", 10)).containsExactly("agent-a");
    }

    @Test
    void aLimitBelowOneIsRejected() {
      var store = new InMemoryScopedStore();
      assertThatThrownBy(() -> store.keys("state", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullKindOnReadThrowsNpeWithAMessage() {
      var store = new InMemoryScopedStore();
      assertThatThrownBy(() -> store.read(null, "agent-a"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("kind");
    }

    @Test
    void nullKeyOnWriteThrowsNpeWithAMessage() {
      var store = new InMemoryScopedStore();
      assertThatThrownBy(() -> store.write("state", null, "v", 0L))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("key");
    }

    @Test
    void nullPayloadOnWriteThrowsNpeWithAMessage() {
      var store = new InMemoryScopedStore();
      assertThatThrownBy(() -> store.write("state", "agent-a", null, 0L))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("payload");
    }

    @Test
    void updatedAtComesFromTheInjectedClock() {
      var fixed = Instant.parse("2026-08-21T12:00:00Z");
      var store = new InMemoryScopedStore(Clock.fixed(fixed, ZoneOffset.UTC));
      store.write("state", "agent-a", "v1", 0L);
      assertThat(store.read("state", "agent-a").orElseThrow().updatedAt()).isEqualTo(fixed);
    }
  }

  @Nested
  class Journal {

    @Test
    void appendAtSeqOneCreatesTheFirstEntry() {
      var store = new InMemoryScopedStore();
      store.append("memory", "agent-a", 1L, "first");
      assertThat(store.entries("memory", "agent-a", 1L))
          .extracting(ScopedStore.Entry::payload)
          .containsExactly("first");
    }

    @Test
    void appendingAtHeadPlusOneAppendsAfterTheExistingEntry() {
      var store = new InMemoryScopedStore();
      store.append("memory", "agent-a", 1L, "first");
      store.append("memory", "agent-a", 2L, "second");
      assertThat(store.entries("memory", "agent-a", 1L))
          .extracting(ScopedStore.Entry::payload)
          .containsExactly("first", "second");
    }

    @Test
    void appendingAtAnOccupiedSeqThrowsConflict() {
      var store = new InMemoryScopedStore();
      store.append("memory", "agent-a", 1L, "first");
      assertThatThrownBy(() -> store.append("memory", "agent-a", 1L, "replacement"))
          .isInstanceOf(ConflictException.class);
    }

    @Test
    void appendingPastAGapIsNotItselfAConflict() {
      var store = new InMemoryScopedStore();
      store.append("memory", "agent-a", 1L, "first");
      store.append("memory", "agent-a", 5L, "farAhead");
      assertThat(store.entries("memory", "agent-a", 1L))
          .extracting(ScopedStore.Entry::seq)
          .containsExactly(1L, 5L);
    }

    @Test
    void entriesFromSeqSlicesInclusively() {
      var store = new InMemoryScopedStore();
      store.append("memory", "agent-a", 1L, "one");
      store.append("memory", "agent-a", 2L, "two");
      store.append("memory", "agent-a", 3L, "three");
      assertThat(store.entries("memory", "agent-a", 2L))
          .extracting(ScopedStore.Entry::payload)
          .containsExactly("two", "three");
    }

    @Test
    void entriesForAnUnknownKeyIsEmpty() {
      var store = new InMemoryScopedStore();
      assertThat(store.entries("memory", "unknown-agent", 1L)).isEmpty();
    }

    @Test
    void appendedAtComesFromTheInjectedClock() {
      var fixed = Instant.parse("2026-08-21T12:00:00Z");
      var store = new InMemoryScopedStore(Clock.fixed(fixed, ZoneOffset.UTC));
      store.append("memory", "agent-a", 1L, "first");
      assertThat(store.entries("memory", "agent-a", 1L).getFirst().appendedAt()).isEqualTo(fixed);
    }

    @Test
    void nullPayloadOnAppendThrowsNpeWithAMessage() {
      var store = new InMemoryScopedStore();
      assertThatThrownBy(() -> store.append("memory", "agent-a", 1L, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("payload");
    }
  }

  @Nested
  class Batch {

    @Test
    void aMixedShapeBatchAppliesEveryOpOnSuccess() {
      var store = new InMemoryScopedStore();
      store.batch(
          List.of(
              new ScopedStore.Op.WriteDocument("state", "agent-a", "v1", 0L),
              new ScopedStore.Op.AppendEntry("memory", "agent-a", 1L, "hello")));
      assertThat(store.read("state", "agent-a").orElseThrow().payload()).isEqualTo("v1");
      assertThat(store.entries("memory", "agent-a", 1L))
          .extracting(ScopedStore.Entry::payload)
          .containsExactly("hello");
    }

    @Test
    void aBatchWithOneStaleOpAppliesNothing() {
      var store = new InMemoryScopedStore();
      store.write("state", "agent-a", "original", 0L);

      assertThatThrownBy(
              () ->
                  store.batch(
                      List.of(
                          new ScopedStore.Op.AppendEntry("memory", "agent-a", 1L, "hello"),
                          new ScopedStore.Op.WriteDocument("state", "agent-a", "stale", 99L))))
          .isInstanceOf(ConflictException.class);

      assertThat(store.read("state", "agent-a").orElseThrow().payload()).isEqualTo("original");
      assertThat(store.entries("memory", "agent-a", 1L)).isEmpty();
    }

    @Test
    void aBatchDeleteRemovesTheDocument() {
      var store = new InMemoryScopedStore();
      store.write("state", "agent-a", "v1", 0L);
      store.batch(List.of(new ScopedStore.Op.DeleteDocument("state", "agent-a", 1L)));
      assertThat(store.read("state", "agent-a")).isEmpty();
    }

    @Test
    void nullOpsThrowsNpeWithAMessage() {
      var store = new InMemoryScopedStore();
      assertThatThrownBy(() -> store.batch(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("ops");
    }
  }

  @Nested
  class Concurrency {

    @Test
    void racingCasWritersProduceExactlyOneWinnerPerRound() throws Exception {
      var store = new InMemoryScopedStore();
      store.write("state", "agent-a", "v0", 0L);
      int racers = 16;
      List<Callable<Boolean>> attempts = new ArrayList<>();
      for (int i = 0; i < racers; i++) {
        attempts.add(
            () -> {
              try {
                store.write("state", "agent-a", "v1", 1L);
                return true;
              } catch (ConflictException _) {
                return false;
              }
            });
      }
      List<Boolean> outcomes = new ArrayList<>();
      try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
        for (var future : pool.invokeAll(attempts)) {
          outcomes.add(future.get());
        }
      }
      assertThat(outcomes).isNotEmpty();
      assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1L);
      assertThat(store.read("state", "agent-a").orElseThrow().version()).isEqualTo(2L);
    }
  }
}
