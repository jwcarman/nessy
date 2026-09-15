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

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.spi.store.Schemas;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code Claims}' own SQL, against real PostgreSQL rather than H2.
 *
 * <p>{@code nessy_claim} carries no time column, so this is not chasing the {@code Instant}-binding
 * defect the way {@code EffectStorePostgresCertificationTest} and {@code
 * AgentStorePostgresCertificationTest} are -- it exists because {@code
 * PostgresStoreCertificationTest} only ever asserted the table EXISTS, and the audit that found the
 * binding defect asked for every store's write path to be run against real PostgreSQL at least
 * once, this one included.
 *
 * <p>Tagged {@code container} and therefore skipped by default: {@code clean verify} must pass with
 * no Docker. Run it with {@code ./mvnw test -Dnessy.excludedGroups=}.
 */
@Tag("container")
@Testcontainers
@DisplayName("Claims' SQL, on PostgreSQL")
class ClaimsPostgresCertificationTest {

  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  private static final AgentId AGENT = AgentId.of("agent-one");
  private static final TurnId TURN = TurnId.of("turn-1");

  private Claims claims;

  @BeforeEach
  void fresh() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    DataSource database = source;
    Schemas.initialize(database);
    JdbcClient.create(database).sql("TRUNCATE TABLE nessy_claim").update();
    claims = new Claims(database);
  }

  @Test
  @DisplayName("a claim written is a claim read back, verbatim")
  void a_claim_round_trips() {
    claims.put(AGENT, TURN, "asked", "the model's question".getBytes());

    assertThat(claims.get(AGENT, TURN, "asked")).contains("the model's question".getBytes());
  }

  @Test
  @DisplayName("writing the same key twice overwrites rather than duplicating")
  void writing_the_same_key_twice_overwrites() {
    claims.put(AGENT, TURN, "asked", "first".getBytes());

    claims.put(AGENT, TURN, "asked", "second".getBytes());

    assertThat(claims.get(AGENT, TURN, "asked")).contains("second".getBytes());
  }

  @Test
  @DisplayName("ending a turn deletes its claims and nothing else's")
  void ending_a_turn_deletes_only_its_own_claims() {
    TurnId otherTurn = TurnId.of("turn-2");
    claims.put(AGENT, TURN, "asked", "mine".getBytes());
    claims.put(AGENT, otherTurn, "asked", "the other turn's".getBytes());

    claims.deleteTurn(AGENT, TURN);

    assertThat(claims.get(AGENT, TURN, "asked")).isEmpty();
    assertThat(claims.get(AGENT, otherTurn, "asked")).contains("the other turn's".getBytes());
  }
}
