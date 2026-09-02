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
package org.jwcarman.nessy.approval.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson2.Jackson2CodecFactory;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.testing.TestDatabase;

class JdbcIntentStoreTest {

  /** A plainly-pinned mapper — tolerant reads, same as the stored format contract. */
  private static final ObjectMapper MAPPER =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  @Nested
  class Declaring {

    @Test
    void anUnwrittenStoreHoldsNoDeclarationBeforeAnyDeclaration() {
      var store =
          new JdbcIntentStore<>(
              TestDatabase.fresh(),
              AgentType.of("chat"),
              AgentId.of("agent-a"),
              Intent.class,
              MAPPER);

      assertThat(store.latest()).isEmpty();
    }

    @Test
    void aSecondDeclarationReplacesTheFirstLastWriteWins() {
      var store =
          new JdbcIntentStore<>(
              TestDatabase.fresh(),
              AgentType.of("chat"),
              AgentId.of("agent-a"),
              Intent.class,
              MAPPER);

      store.declare(new Intent("first declaration"));
      store.declare(new Intent("second declaration"));

      assertThat(store.latest()).contains(new Intent("second declaration"));
    }

    @Test
    void aStoredDeclarationWithAnUnknownFieldStillReads() {
      var database = TestDatabase.fresh();
      storeDeclaration(
          database,
          "agent-a",
          "{\"declaration\":\"restart prod-eu\",\"futureField\":\"not yet invented\"}");
      var store =
          new JdbcIntentStore<>(
              database, AgentType.of("chat"), AgentId.of("agent-a"), Intent.class, MAPPER);

      assertThat(store.latest()).contains(new Intent("restart prod-eu"));
    }
  }

  @Nested
  class Two_store_views_over_one_substrate {

    @Test
    void shareTheDeclaration() {
      var database = TestDatabase.fresh();
      var writer =
          new JdbcIntentStore<>(
              database, AgentType.of("chat"), AgentId.of("agent-a"), Intent.class, MAPPER);
      var reader =
          new JdbcIntentStore<>(
              database, AgentType.of("chat"), AgentId.of("agent-a"), Intent.class, MAPPER);

      writer.declare(new Intent("restart prod-eu to clear the stuck deploy"));

      assertThat(reader.latest()).contains(new Intent("restart prod-eu to clear the stuck deploy"));
    }
  }

  @Nested
  class Declare_over_a_foreign_shape {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({@JsonSubTypes.Type(value = Restart.class, name = "Restart")})
    sealed interface ForeignVocabulary permits Restart {}

    record Restart(String target, String reason) implements ForeignVocabulary {}

    /**
     * Typed-stores fix round 1, Q3: {@code declare} is documented "last write wins" without
     * qualification — {@link DocumentStore#update} would decode the incumbent before discarding it,
     * which throws when a second store shares the key with an incompatible vocabulary, silently
     * narrowing that contract. A version-only CAS loop restores the blind overwrite.
     */
    @Test
    void blindlyOverwritesAnIncumbentItsOwnCodecCannotDecode() {
      var database = TestDatabase.fresh();
      var plainStore =
          new JdbcIntentStore<>(
              database, AgentType.of("chat"), AgentId.of("agent-a"), Intent.class, MAPPER);
      var foreignStore =
          new JdbcIntentStore<>(
              database,
              AgentType.of("chat"),
              AgentId.of("agent-a"),
              ForeignVocabulary.class,
              MAPPER);
      plainStore.declare(new Intent("a plain declaration, no \"type\" discriminator at all"));

      foreignStore.declare(new Restart("prod-eu", "stuck deploy"));

      assertThat(foreignStore.latest()).contains(new Restart("prod-eu", "stuck deploy"));
    }
  }

  @Nested
  @DisplayName("whose intent it is")
  class Identity {

    @Test
    @DisplayName("an id is unique within its type, so two types do not share a declaration")
    void two_agent_types_sharing_an_id_declare_separately() {
      var database = TestDatabase.fresh();
      var chat =
          new JdbcIntentStore<>(
              database, AgentType.of("chat"), AgentId.of("agent-a"), Intent.class, MAPPER);
      var watchman =
          new JdbcIntentStore<>(
              database, AgentType.of("watchman"), AgentId.of("agent-a"), Intent.class, MAPPER);

      chat.declare(new Intent("answering a question"));
      watchman.declare(new Intent("pruning docker images"));

      assertThat(chat.latest())
          .as("keyed on the id alone, the watchman's declaration overwrote the chat agent's")
          .contains(new Intent("answering a question"));
      assertThat(watchman.latest()).contains(new Intent("pruning docker images"));
    }
  }

  @Nested
  class Sealed_vocabulary {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
      @JsonSubTypes.Type(value = Restart.class, name = "Restart"),
      @JsonSubTypes.Type(value = Diagnose.class, name = "Diagnose")
    })
    sealed interface OpsIntent permits Restart, Diagnose {}

    record Restart(String target, String reason) implements OpsIntent {}

    record Diagnose(String target) implements OpsIntent {}

    @Test
    void aDeclarationRoundTripsThroughTheClassToken() {
      var store =
          new JdbcIntentStore<>(
              TestDatabase.fresh(),
              AgentType.of("chat"),
              AgentId.of("agent-a"),
              OpsIntent.class,
              MAPPER);

      store.declare(new Restart("prod-eu", "stuck deploy"));

      assertThat(store.latest()).contains(new Restart("prod-eu", "stuck deploy"));
    }

    @Test
    void aDifferentPermittedShapeRoundTripsThroughTheClassTokenToo() {
      var store =
          new JdbcIntentStore<>(
              TestDatabase.fresh(),
              AgentType.of("chat"),
              AgentId.of("agent-a"),
              OpsIntent.class,
              MAPPER);

      store.declare(new Diagnose("prod-eu"));

      assertThat(store.latest()).contains(new Diagnose("prod-eu"));
    }

    /**
     * json-repeal task 2: once {@code OpsIntent} carries
     * {@code @JsonTypeInfo}/{@code @JsonSubTypes} (needed for {@code Schemas}/tool-input binding
     * too), {@code Codec.json} defers wholly to Jackson's own polymorphic machinery — a single
     * {@code "type"} discriminator, never a double one. Pinned here by asserting the raw stored
     * bytes carry exactly one {@code "type"} key.
     */
    @Test
    void anAnnotatedVocabularySingleDiscriminatesRatherThanDoublingTheTypeKey() {
      var database = TestDatabase.fresh();
      var store =
          new JdbcIntentStore<>(
              database, AgentType.of("chat"), AgentId.of("agent-a"), OpsIntent.class, MAPPER);

      store.declare(new Restart("prod-eu", "stuck deploy"));

      String rawJson = declarationIn(database, "agent-a");
      assertThat(rawJson.split("\"type\"", -1)).hasSize(2);
    }
  }

  @Nested
  class A_cas_conflict_on_declare {

    /**
     * Pinned by {@link #a_lost_optimistic_lock_retries_rather_than_dropping_the_write()}: with the
     * DataSource wrapper below removed to isolate what this test alone actually exercises, {@code
     * declare}'s first call always takes the INSERT branch (no row exists yet), and INSERT never
     * reads back the row count {@code losesOneWrite} fakes — so despite its name, this test never
     * reached the conditional-UPDATE retry at all; it passed because the underlying INSERT it
     * ignored the fake return value from still landed for real. Kept as a regression pin on the
     * INSERT path (a first write with no contender always succeeds), but the name is corrected to
     * say so, and the actual CAS-retry paths are covered by the two tests below instead.
     */
    @Test
    void aFirstWriteWithNoContenderSucceedsEvenWhenItsReportedRowCountIsWrong() {
      var database = TestDatabase.fresh();
      var raced =
          new JdbcIntentStore<>(
              losesOneWrite(database),
              AgentType.of("chat"),
              AgentId.of("agent-a"),
              Intent.class,
              MAPPER);

      raced.declare(new Intent("restart prod-eu to clear the stuck deploy"));

      var readBack =
          new JdbcIntentStore<>(
              database, AgentType.of("chat"), AgentId.of("agent-a"), Intent.class, MAPPER);
      assertThat(readBack.latest())
          .contains(new Intent("restart prod-eu to clear the stuck deploy"));
    }

    @Test
    @DisplayName(
        "an insert that loses to a duplicate key retries against the row the other caller just"
            + " made, rather than failing")
    void anInsertThatLosesToADuplicateKeyRetriesAgainstTheRow() {
      var database = TestDatabase.fresh();
      // Simulates another caller's declaration having already landed, between this store's
      // (stale) empty read of the version and its own attempt to insert.
      storeDeclaration(database, "agent-a", "{\"declaration\":\"won the race\"}");
      var raced =
          new JdbcIntentStore<>(
              forcedEmptyVersionReadOnce(database),
              AgentType.of("chat"),
              AgentId.of("agent-a"),
              Intent.class,
              MAPPER);

      raced.declare(new Intent("declared after losing the insert race"));

      var readBack =
          new JdbcIntentStore<>(
              database, AgentType.of("chat"), AgentId.of("agent-a"), Intent.class, MAPPER);
      assertThat(readBack.latest()).contains(new Intent("declared after losing the insert race"));
    }

    @Test
    @DisplayName(
        "a write that loses the optimistic-lock race retries instead of dropping the caller's"
            + " declaration")
    void a_lost_optimistic_lock_retries_rather_than_dropping_the_write() {
      var database = TestDatabase.fresh();
      var writer =
          new JdbcIntentStore<>(
              database, AgentType.of("chat"), AgentId.of("agent-a"), Intent.class, MAPPER);
      writer.declare(new Intent("first declaration"));

      var raced =
          new JdbcIntentStore<>(
              racingAnotherWriterIntoTheFirstUpdate(database),
              AgentType.of("chat"),
              AgentId.of("agent-a"),
              Intent.class,
              MAPPER);
      raced.declare(new Intent("declared after losing the update race"));

      assertThat(writer.latest()).contains(new Intent("declared after losing the update race"));
    }
  }

  @Nested
  class A_custom_codec {

    @Test
    void isHonoredByBothWritesAndReads() {
      var database = TestDatabase.fresh();
      Codec<Intent> codec =
          new Jackson2CodecFactory(MAPPER).create(Intent.class).andThen(new MarkerBytesCodec());
      var store =
          new JdbcIntentStore<>(database, AgentType.of("chat"), AgentId.of("agent-a"), codec);

      store.declare(new Intent("restart prod-eu"));

      byte[] rawPayload = declarationIn(database, "agent-a").getBytes(StandardCharsets.UTF_8);
      assertThat(MarkerBytesCodec.isMarked(rawPayload)).isTrue();
      assertThat(store.latest()).contains(new Intent("restart prod-eu"));
    }
  }

  /**
   * A trivial byte-transform codec: prepends a fixed marker to every encoded payload and strips it
   * back off on decode. Chained onto a {@code Codec<T>} via {@link Codec#andThen(Codec)}, it proves
   * a caller-supplied codec is actually honored by the recipe — the raw stored bytes carry the
   * marker, and a read still round-trips through it. A local hand-rolled equivalent of
   * nessy-agent's own {@code MarkerBytesCodec} test support, which this module cannot depend on
   * (design authority: nessy-intent depends only on nessy-api and nessy-spi).
   */
  private static final class MarkerBytesCodec implements Codec<byte[]> {

    private static final byte[] MARKER = "MARKER:".getBytes(StandardCharsets.UTF_8);

    @Override
    public byte[] encode(byte[] value) {
      byte[] marked = new byte[MARKER.length + value.length];
      System.arraycopy(MARKER, 0, marked, 0, MARKER.length);
      System.arraycopy(value, 0, marked, MARKER.length, value.length);
      return marked;
    }

    @Override
    public byte[] decode(byte[] bytes) {
      return Arrays.copyOfRange(bytes, MARKER.length, bytes.length);
    }

    static boolean isMarked(byte[] payload) {
      return payload.length >= MARKER.length
          && Arrays.equals(payload, 0, MARKER.length, MARKER, 0, MARKER.length);
    }
  }

  /** Puts a declaration in the table directly, so a test can store a shape the store would not. */
  private static void storeDeclaration(
      javax.sql.DataSource dataSource, String agentId, String declaration) {
    org.springframework.jdbc.core.simple.JdbcClient.create(dataSource)
        .sql(
            "INSERT INTO nessy_intent (agent_type, agent_id, declaration, version) VALUES ('chat', ?, ?, 1)")
        .params(agentId, declaration)
        .update();
  }

  /** The stored bytes for an agent, read past the store rather than through it. */
  private static String declarationIn(javax.sql.DataSource dataSource, String agentId) {
    return org.springframework.jdbc.core.simple.JdbcClient.create(dataSource)
        .sql("SELECT declaration FROM nessy_intent WHERE agent_type = 'chat' AND agent_id = ?")
        .params(agentId)
        .query(String.class)
        .single();
  }

  /**
   * A {@link javax.sql.DataSource} whose FIRST update reports that it changed nothing.
   *
   * <p>That is exactly what a lost compare-and-set looks like to the store: the row's version moved
   * between the read and the write, so the conditional update matches no row. The store must read
   * the version again and retry rather than assume it won — which is the behaviour under test, and
   * which is otherwise only reachable by racing two threads and hoping.
   */
  private static javax.sql.DataSource losesOneWrite(javax.sql.DataSource delegate) {
    boolean[] lost = {false};
    return (javax.sql.DataSource)
        java.lang.reflect.Proxy.newProxyInstance(
            JdbcIntentStoreTest.class.getClassLoader(),
            new Class<?>[] {javax.sql.DataSource.class},
            (source, method, args) -> {
              Object result = method.invoke(delegate, args);
              return result instanceof java.sql.Connection connection
                  ? proxyConnection(connection, lost)
                  : result;
            });
  }

  private static java.sql.Connection proxyConnection(java.sql.Connection delegate, boolean[] lost) {
    return (java.sql.Connection)
        java.lang.reflect.Proxy.newProxyInstance(
            JdbcIntentStoreTest.class.getClassLoader(),
            new Class<?>[] {java.sql.Connection.class},
            (connection, method, args) -> {
              Object result = method.invoke(delegate, args);
              return result instanceof java.sql.PreparedStatement statement
                  ? proxyStatement(statement, lost)
                  : result;
            });
  }

  private static java.sql.PreparedStatement proxyStatement(
      java.sql.PreparedStatement delegate, boolean[] lost) {
    return (java.sql.PreparedStatement)
        java.lang.reflect.Proxy.newProxyInstance(
            JdbcIntentStoreTest.class.getClassLoader(),
            new Class<?>[] {java.sql.PreparedStatement.class},
            (statement, method, args) -> {
              if ("executeUpdate".equals(method.getName()) && !lost[0]) {
                lost[0] = true;
                delegate.executeUpdate();
                return 0;
              }
              return method.invoke(delegate, args);
            });
  }

  /**
   * A {@link javax.sql.DataSource} whose first read of the version sees nothing, even though a row
   * is already there.
   *
   * <p>That is what a stale read looks like from {@code currentVersion()}'s point of view when
   * another caller's declaration lands between the read and this caller's own insert: the SELECT is
   * rewritten, once, to a condition that legitimately matches no row — a real query executed
   * against the real database, not a fabricated result set — so the store's own INSERT collides
   * with the row that is actually there and must catch the resulting {@code DuplicateKeyException}.
   */
  private static javax.sql.DataSource forcedEmptyVersionReadOnce(javax.sql.DataSource delegate) {
    boolean[] intercepted = {false};
    return (javax.sql.DataSource)
        java.lang.reflect.Proxy.newProxyInstance(
            JdbcIntentStoreTest.class.getClassLoader(),
            new Class<?>[] {javax.sql.DataSource.class},
            (source, method, args) -> {
              Object result = method.invoke(delegate, args);
              return result instanceof java.sql.Connection connection
                  ? proxyConnectionForcingEmptyVersionRead(connection, intercepted)
                  : result;
            });
  }

  private static java.sql.Connection proxyConnectionForcingEmptyVersionRead(
      java.sql.Connection delegate, boolean[] intercepted) {
    return (java.sql.Connection)
        java.lang.reflect.Proxy.newProxyInstance(
            JdbcIntentStoreTest.class.getClassLoader(),
            new Class<?>[] {java.sql.Connection.class},
            (connection, method, args) -> {
              if ("prepareStatement".equals(method.getName())
                  && args.length == 1
                  && args[0] instanceof String sql
                  && sql.startsWith("SELECT version")
                  && !intercepted[0]) {
                intercepted[0] = true;
                return delegate.prepareStatement(sql + " AND 1 = 0");
              }
              return method.invoke(delegate, args);
            });
  }

  /**
   * A {@link javax.sql.DataSource} on which the first attempt to carry out the store's own
   * conditional UPDATE loses to another caller's write.
   *
   * <p>Right before that UPDATE runs — still bound to the version this caller read — a second, real
   * writer bumps the row's version out from under it, over its own connection. The bound UPDATE
   * then legitimately matches no row, the way it would if two callers had genuinely declared at the
   * same moment, and the store must read the version again and retry.
   */
  private static javax.sql.DataSource racingAnotherWriterIntoTheFirstUpdate(
      javax.sql.DataSource delegate) {
    boolean[] raced = {false};
    return (javax.sql.DataSource)
        java.lang.reflect.Proxy.newProxyInstance(
            JdbcIntentStoreTest.class.getClassLoader(),
            new Class<?>[] {javax.sql.DataSource.class},
            (source, method, args) -> {
              Object result = method.invoke(delegate, args);
              return result instanceof java.sql.Connection connection
                  ? proxyConnectionRacingTheUpdate(connection, delegate, raced)
                  : result;
            });
  }

  private static java.sql.Connection proxyConnectionRacingTheUpdate(
      java.sql.Connection delegate, javax.sql.DataSource realDataSource, boolean[] raced) {
    return (java.sql.Connection)
        java.lang.reflect.Proxy.newProxyInstance(
            JdbcIntentStoreTest.class.getClassLoader(),
            new Class<?>[] {java.sql.Connection.class},
            (connection, method, args) -> {
              Object result = method.invoke(delegate, args);
              return result instanceof java.sql.PreparedStatement statement
                  ? proxyStatementRacingTheUpdate(statement, realDataSource, raced)
                  : result;
            });
  }

  private static java.sql.PreparedStatement proxyStatementRacingTheUpdate(
      java.sql.PreparedStatement delegate, javax.sql.DataSource realDataSource, boolean[] raced) {
    return (java.sql.PreparedStatement)
        java.lang.reflect.Proxy.newProxyInstance(
            JdbcIntentStoreTest.class.getClassLoader(),
            new Class<?>[] {java.sql.PreparedStatement.class},
            (statement, method, args) -> {
              if ("executeUpdate".equals(method.getName()) && !raced[0]) {
                raced[0] = true;
                org.springframework.jdbc.core.simple.JdbcClient.create(realDataSource)
                    .sql(
                        "UPDATE nessy_intent SET version = version + 1"
                            + " WHERE agent_type = 'chat' AND agent_id = 'agent-a'")
                    .update();
              }
              return method.invoke(delegate, args);
            });
  }
}
