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
package org.jwcarman.nessy.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.store.InMemoryScopedStore;
import org.jwcarman.nessy.spi.store.ScopedStore;

class StoredIntentStoreTest {

  @Nested
  class Declaring {

    @Test
    void anUnwrittenStoreHoldsNoDeclarationBeforeAnyDeclaration() {
      var store = new StoredIntentStore<>(new InMemoryScopedStore(), "agent-a", Intent.class);

      assertThat(store.latest()).isEmpty();
    }

    @Test
    void aSecondDeclarationReplacesTheFirstLastWriteWins() {
      var store = new StoredIntentStore<>(new InMemoryScopedStore(), "agent-a", Intent.class);

      store.declare(new Intent("first declaration"));
      store.declare(new Intent("second declaration"));

      assertThat(store.latest()).contains(new Intent("second declaration"));
    }

    @Test
    void aStoredDeclarationWithAnUnknownFieldStillReads() {
      var kernel = new InMemoryScopedStore();
      kernel.write(
          "intent",
          "agent-a",
          "{\"declaration\":\"restart prod-eu\",\"futureField\":\"not yet invented\"}",
          0);
      var store = new StoredIntentStore<>(kernel, "agent-a", Intent.class);

      assertThat(store.latest()).contains(new Intent("restart prod-eu"));
    }
  }

  @Nested
  class Two_store_views_over_one_kernel {

    @Test
    void shareTheDeclaration() {
      var kernel = new InMemoryScopedStore();
      var writer = new StoredIntentStore<>(kernel, "agent-a", Intent.class);
      var reader = new StoredIntentStore<>(kernel, "agent-a", Intent.class);

      writer.declare(new Intent("restart prod-eu to clear the stuck deploy"));

      assertThat(reader.latest()).contains(new Intent("restart prod-eu to clear the stuck deploy"));
    }
  }

  @Nested
  class Sealed_vocabulary {

    sealed interface OpsIntent permits Restart, Diagnose {}

    record Restart(String target, String reason) implements OpsIntent {}

    record Diagnose(String target) implements OpsIntent {}

    @Test
    void aDeclarationRoundTripsThroughTheClassToken() {
      var store = new StoredIntentStore<>(new InMemoryScopedStore(), "agent-a", OpsIntent.class);

      store.declare(new Restart("prod-eu", "stuck deploy"));

      assertThat(store.latest()).contains(new Restart("prod-eu", "stuck deploy"));
    }

    @Test
    void aDifferentPermittedShapeRoundTripsThroughTheClassTokenToo() {
      var store = new StoredIntentStore<>(new InMemoryScopedStore(), "agent-a", OpsIntent.class);

      store.declare(new Diagnose("prod-eu"));

      assertThat(store.latest()).contains(new Diagnose("prod-eu"));
    }
  }

  @Nested
  class A_cas_conflict_on_declare {

    @Test
    void retriesAndTheRetriedDeclarationStillWins() {
      var kernel = new InMemoryScopedStore();
      var raced =
          new StoredIntentStore<>(new RaceOnceOnWriteStore(kernel), "agent-a", Intent.class);

      raced.declare(new Intent("restart prod-eu to clear the stuck deploy"));

      var readBack = new StoredIntentStore<>(kernel, "agent-a", Intent.class);
      assertThat(readBack.latest())
          .contains(new Intent("restart prod-eu to clear the stuck deploy"));
    }
  }

  /**
   * Simulates one lost race on {@link #write}: the first call is preceded by a competitor's write
   * landing first at the very {@code expectedVersion} the caller is targeting, so the delegate
   * throws a genuine {@code ConflictException}; every later write goes straight through. A local
   * hand-rolled equivalent of {@code RaceOnceOnWriteStore} (nessy-agent's own test support), which
   * this module cannot depend on (design authority: nessy-intent depends only on nessy-api and
   * nessy-spi).
   */
  private static final class RaceOnceOnWriteStore implements ScopedStore {

    private final ScopedStore delegate;
    private boolean raced;

    private RaceOnceOnWriteStore(ScopedStore delegate) {
      this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public Optional<Document> read(String kind, String key) {
      return delegate.read(kind, key);
    }

    @Override
    public void write(String kind, String key, String payload, long expectedVersion) {
      if (!raced) {
        raced = true;
        delegate.write(kind, key, "{\"declaration\":\"a competing declaration\"}", expectedVersion);
      }
      delegate.write(kind, key, payload, expectedVersion);
    }

    @Override
    public void delete(String kind, String key, long expectedVersion) {
      delegate.delete(kind, key, expectedVersion);
    }

    @Override
    public List<String> keys(String kind, int limit) {
      return delegate.keys(kind, limit);
    }

    @Override
    public void append(String kind, String key, long expectedSeq, String payload) {
      delegate.append(kind, key, expectedSeq, payload);
    }

    @Override
    public List<Entry> entries(String kind, String key, long fromSeq) {
      return delegate.entries(kind, key, fromSeq);
    }

    @Override
    public void batch(List<Op> ops) {
      delegate.batch(ops);
    }
  }
}
