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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.spi.store.Schemas;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code AgentStore}'s own SQL, against real PostgreSQL rather than H2.
 *
 * <p><b>Why this exists.</b> {@code AgentStore} bound {@code last_touched_at} as a bare {@link
 * Instant} -- {@code TOUCH}, {@code UPDATE} and the {@code INSERT} a first-ever {@code lockAndLoad}
 * performs all did. H2 tolerated it silently; PostgreSQL's driver cannot infer a SQL type for
 * {@code java.time.Instant} and throws. {@code PostgresStoreCertificationTest} only ever asserted
 * that {@code nessy_agent} EXISTS -- nothing had ever written a row through it against real
 * PostgreSQL -- which is exactly how that defect survived every green build. This exercises the
 * WRITE path: every statement that carries a time parameter, run at least once here.
 *
 * <p>Lives in {@code AgentStore}'s own package, in this module, for the same reason {@code
 * EffectStorePostgresCertificationTest} does: a Testcontainers dependency does not belong on the
 * engine's own test classpath, and {@code AgentStore} is package-private.
 *
 * <p>Tagged {@code container} and therefore skipped by default: {@code clean verify} must pass with
 * no Docker. Run it with {@code ./mvnw test -Dnessy.excludedGroups=}.
 */
@Tag("container")
@Testcontainers
@DisplayName("AgentStore's SQL, on PostgreSQL")
class AgentStorePostgresCertificationTest {

  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  private static final AgentType TYPE = AgentType.of("chat");
  private static final AgentId AGENT = AgentId.of("agent-one");
  private static final AgentId OTHER = AgentId.of("agent-two");
  private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

  private DataSource database;
  private AgentStore store;
  private TransactionTemplate transactions;

  @BeforeEach
  void fresh() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    database = source;
    Schemas.initialize(database);
    JdbcClient.create(database).sql("TRUNCATE TABLE nessy_agent").update();
    store = new AgentStore(database, Clock.fixed(NOW, ZoneOffset.UTC));
    transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
  }

  @Nested
  @DisplayName("create, via lockAndLoad's own INSERT")
  class Create {

    /**
     * {@code INSERT ... last_touched_at} -- the site that could never write a row on PostgreSQL.
     */
    @Test
    @DisplayName("an unknown agent is created idle, with a real last_touched_at")
    void an_unknown_agent_is_created_idle() {
      AgentState loaded = transactions.execute(status -> store.lockAndLoad(TYPE, AGENT));

      assertThat(loaded).isEqualTo(AgentState.idle());
    }
  }

  @Nested
  @DisplayName("save, via UPDATE")
  class Save {

    /** {@code UPDATE ... last_touched_at} -- the second raw-{@code Instant} site. */
    @Test
    @DisplayName("what was saved is what comes back")
    void a_saved_state_round_trips() {
      AgentState working = AgentState.idle().taking(TurnId.of("turn-1"), "claim-1");

      transactions.executeWithoutResult(status -> store.save(TYPE, AGENT, working));

      AgentState loaded = transactions.execute(status -> store.lockAndLoad(TYPE, AGENT));
      assertThat(loaded).isEqualTo(working);
    }
  }

  @Nested
  @DisplayName("touch")
  class Touch {

    /**
     * {@code TOUCH} -- the third raw-{@code Instant} site, and the one the finding named first. A
     * second store, fixed two minutes LATER, stands in for the clock actually advancing between the
     * save and the touch -- {@code store} itself is fixed, precisely so {@code stalled}'s own
     * {@code before} argument stays a known quantity throughout.
     */
    @Test
    @DisplayName("touching a stalled agent moves it out of the next stalled sweep")
    void touching_moves_last_touched_at_forward() {
      AgentStore later =
          new AgentStore(database, Clock.fixed(NOW.plus(Duration.ofMinutes(2)), ZoneOffset.UTC));
      transactions.executeWithoutResult(
          status -> store.save(TYPE, AGENT, AgentState.idle().taking(TurnId.of("t"), "c")));
      assertThat(store.stalled(TYPE, NOW.plus(Duration.ofMinutes(1)), 10)).containsExactly(AGENT);

      transactions.executeWithoutResult(status -> later.touch(TYPE, AGENT));

      assertThat(store.stalled(TYPE, NOW.plus(Duration.ofMinutes(1)), 10)).isEmpty();
    }
  }

  @Nested
  @DisplayName("stalled")
  class Stalled {

    /** {@code SELECT ... WHERE last_touched_at < ?} -- the parameter {@code before} binds. */
    @Test
    @DisplayName("a busy agent that has not moved is stalled; an idle one never is")
    void stalled_finds_only_agents_stuck_mid_turn() {
      transactions.executeWithoutResult(
          status -> store.save(TYPE, AGENT, AgentState.idle().taking(TurnId.of("t"), "c")));
      transactions.executeWithoutResult(status -> store.save(TYPE, OTHER, AgentState.idle()));

      assertThat(store.stalled(TYPE, NOW.plus(Duration.ofMinutes(1)), 10)).containsExactly(AGENT);
    }
  }
}
