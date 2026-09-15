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

import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.agent.Effect;
import org.jwcarman.nessy.spi.store.Schemas;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code EffectStore}'s own SQL, against real PostgreSQL rather than H2 -- the module {@code
 * EffectStore} lives in cannot certify against Postgres itself (a Testcontainers dependency does
 * not belong on the engine's own test classpath), so this lives here instead, in {@code
 * EffectStore}'s own PACKAGE so it can reach the package-private store and its package-private
 * {@code Attempted} record directly, exactly the way {@code EffectStoreTest} does inside {@code
 * nessy-engine} itself.
 *
 * <p><b>Why this is the single most load-bearing test on the branch.</b> The whole exhaustion
 * guarantee -- a chronically failing obligation gives up after a bounded number of attempts, never
 * fewer, never more -- rests on {@link EffectStore#TAKE}'s conditional {@code attempts = attempts +
 * CASE WHEN status IN (?, ?) THEN 1 ELSE 0 END} evaluating the {@code CASE} against the PRE-update
 * row. That was measured on H2 only; this measures it on the database people actually deploy.
 *
 * <p>Tagged {@code container} and therefore skipped by default: {@code clean verify} must pass with
 * no Docker. Run it with {@code ./mvnw test -Dnessy.excludedGroups=}.
 */
@Tag("container")
@Testcontainers
@DisplayName("EffectStore's SQL, on PostgreSQL")
class EffectStorePostgresCertificationTest {

  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  private static final AgentType TYPE = AgentType.of("chat");
  private static final AgentId AGENT = AgentId.of("agent-one");
  private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

  private DataSource database;
  private EffectStore store;

  @BeforeEach
  void fresh() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    database = source;
    Schemas.initialize(database);
    JdbcClient.create(database).sql("TRUNCATE TABLE nessy_effect").update();
    store = new EffectStore(database);
  }

  @Nested
  @DisplayName("TAKE's conditional attempts increment")
  class TheTakeConditional {

    /**
     * The row people worry about: something died mid-attempt and left the row {@code RUNNING} past
     * its watchdog. {@code SELECT_DUE} already proved {@code actionable_at <= now}, so the ONLY way
     * {@link EffectStore#attempt} finds a {@code RUNNING} row here is a stalled one -- and that
     * must count as a failure.
     */
    @Test
    @DisplayName("a row found RUNNING past its watchdog counts one failure")
    void a_running_row_counts_one_failure() {
      CallId callId = CallId.of("call-running");
      insertRaw(callId, "RUNNING", 0, NOW);

      var due = store.attempt(TYPE, 10, NOW.plusSeconds(1), java.time.Duration.ofMinutes(5));

      assertThat(due).hasSize(1);
      assertThat(due.get(0).attempts()).isEqualTo(1);
      assertThat(due.get(0).parked()).isFalse();
    }

    /**
     * A fresh {@code PENDING} row's first-ever attempt counts nothing -- its previous attempt (if
     * any) already recorded its own failure on the way out, via {@code retry}, so double-counting
     * it here would be wrong in the other direction.
     */
    @Test
    @DisplayName("a fresh PENDING row's first attempt counts nothing")
    void a_pending_row_counts_nothing() {
      CallId callId = CallId.of("call-pending");
      store.insert(
          TYPE,
          AGENT,
          null,
          callId,
          0,
          EffectStore.PAYLOADS.encode(new Effect.RunTool(callId, "a_tool")),
          null);

      var due =
          store.attempt(TYPE, 10, Instant.now().plusSeconds(1), java.time.Duration.ofMinutes(5));

      assertThat(due).hasSize(1);
      assertThat(due.get(0).attempts()).isEqualTo(0);
      assertThat(due.get(0).parked()).isFalse();
    }
  }

  @Nested
  @DisplayName("deferSiblings' SQL")
  class DeferSiblings {

    /**
     * {@code COALESCE(turn_id, '') = :turn}, bound to {@code ""} for the turn-less rows {@code
     * TakeWork} decides before any turn exists -- H2 accepts {@code turn_id = NULL} matching
     * nothing the same way standard SQL does, but PostgreSQL's own NULL comparison semantics are
     * exactly what this statement exists to work around, so it is worth measuring here too.
     */
    @Test
    @DisplayName("a turn-less row (turn_id NULL) is still reached via COALESCE")
    void a_turn_less_row_is_reached_via_coalesce() {
      CallId callId = CallId.of("call-turnless");
      store.insert(
          TYPE,
          AGENT,
          null,
          callId,
          5,
          EffectStore.PAYLOADS.encode(new Effect.RunTool(callId, "a_tool")),
          null);

      int pushed =
          store.deferSiblings(TYPE, AGENT, null, 0, java.util.List.of(), NOW.plusSeconds(30));

      assertThat(pushed).isEqualTo(1);
    }

    /** The named-parameter {@code IN (:ids)} expansion, reaching rows outside the turn clause. */
    @Test
    @DisplayName("rows named explicitly via IN (:ids) are reached, even outside the turn clause")
    void rows_named_via_in_ids_are_reached() {
      TurnId turnOne = TurnId.of("turn-1");
      TurnId turnTwo = TurnId.of("turn-2");
      CallId inTurn = CallId.of("call-in-turn");
      CallId otherTurn = CallId.of("call-other-turn");
      store.insert(
          TYPE,
          AGENT,
          turnOne,
          inTurn,
          5,
          EffectStore.PAYLOADS.encode(new Effect.RunTool(inTurn, "a_tool")),
          null);
      EffectId namedId =
          store.insert(
              TYPE,
              AGENT,
              turnTwo,
              otherTurn,
              0,
              EffectStore.PAYLOADS.encode(new Effect.RunTool(otherTurn, "a_tool")),
              null);

      int pushed =
          store.deferSiblings(
              TYPE, AGENT, turnOne, 0, java.util.List.of(namedId), NOW.plusSeconds(30));

      assertThat(pushed).isEqualTo(2);
    }
  }

  /**
   * Inserts a row directly at a given status -- {@link EffectStore#insert} only ever writes
   * PENDING.
   */
  private void insertRaw(CallId callId, String status, int attempts, Instant actionableAt) {
    byte[] payload = EffectStore.PAYLOADS.encode(new Effect.RunTool(callId, "a_tool"));
    JdbcClient.create(database)
        .sql(
            "INSERT INTO nessy_effect"
                + " (effect_id, agent_type, agent_id, turn_id, call_id, ordinal, payload,"
                + " observability, status, attempts, actionable_at, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .param(EffectId.next().value())
        .param(TYPE.name())
        .param(AGENT.value())
        .param((String) null)
        .param(callId.value())
        .param(0)
        .param(payload)
        .param((String) null)
        .param(status)
        .param(attempts)
        .param(Timestamp.from(actionableAt))
        .param(Timestamp.from(actionableAt))
        .update();
  }
}
