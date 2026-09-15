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
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.backlog.BacklogItem;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.spi.store.Schemas;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code BacklogStore}'s own SQL, against real PostgreSQL rather than H2.
 *
 * <p>{@code nessy_backlog.received_at} and {@code nessy_poison.offered_at} are both {@code
 * TIMESTAMP WITH TIME ZONE}, and both are written through {@link
 * JdbcTimestamps#ts(java.time.Instant)} -- {@code offer}'s {@code INSERT} and {@code poison}'s own
 * {@code INSERT}. {@code PostgresStoreCertificationTest} only ever asserted {@code nessy_backlog}
 * EXISTS; this runs the write path that carries those parameters at least once against the database
 * people deploy.
 *
 * <p>Lives in {@code BacklogStore}'s own package, in this module, for the same reason {@code
 * EffectStorePostgresCertificationTest} does.
 *
 * <p>Tagged {@code container} and therefore skipped by default: {@code clean verify} must pass with
 * no Docker. Run it with {@code ./mvnw test -Dnessy.excludedGroups=}.
 */
@Tag("container")
@Testcontainers
@DisplayName("BacklogStore's SQL, on PostgreSQL")
class BacklogStorePostgresCertificationTest {

  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  private static final AgentId AGENT = AgentId.of("agent-one");

  /**
   * Keeps everything offered, in arrival order -- the simplest coalescer that still writes rows.
   */
  private static final BacklogCoalescer<String> KEEP_ALL =
      (waiting, arrival) -> {
        List<BacklogItem<String>> all = new ArrayList<>(waiting);
        all.add(arrival);
        return all;
      };

  private DataSource database;
  private BacklogStore<String> store;
  private Claims claims;

  @BeforeEach
  void fresh() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    database = source;
    Schemas.initialize(database);
    JdbcClient jdbc = JdbcClient.create(database);
    jdbc.sql("TRUNCATE TABLE nessy_backlog").update();
    jdbc.sql("TRUNCATE TABLE nessy_poison").update();
    jdbc.sql("TRUNCATE TABLE nessy_claim").update();
    claims = new Claims(database);
    store =
        new BacklogStore<>(
            database,
            claims,
            JsonCodec.of(EngineMapper.INSTANCE, String.class),
            JsonCodec.of(EngineMapper.INSTANCE, UserMessage.class),
            UserMessage::of,
            KEEP_ALL,
            Clock.systemUTC());
  }

  @Nested
  @DisplayName("offer, via received_at's own INSERT")
  class Offer {

    /** {@code INSERT ... received_at} -- the row {@code offer} writes for a fresh observation. */
    @Test
    @DisplayName("an offered observation is waiting to be taken")
    void an_offered_observation_is_waiting() {
      store.offer(AGENT, "hello");

      BacklogStore.TakeResult taken = store.take(AGENT, null);

      assertThat(taken).isInstanceOf(BacklogStore.TakeResult.Work.class);
    }
  }

  @Nested
  @DisplayName("poison, via offered_at's own INSERT")
  class Poison {

    /** {@code nessy_poison.offered_at} -- a second time column, a second table, own statement. */
    @Test
    @DisplayName("a poisoned agent's next take reports it is done, not what is waiting")
    void a_poisoned_agent_is_told_before_its_queued_work() {
      store.offer(AGENT, "hello");

      store.poison(AGENT);

      assertThat(store.take(AGENT, null)).isInstanceOf(BacklogStore.TakeResult.Poisoned.class);
    }
  }
}
