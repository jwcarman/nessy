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

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

@DisplayName("A turn's scratch space")
class ClaimsTest {

  private static final AgentId HOUSE = AgentId.of("house-12");

  private EmbeddedDatabase database;
  private Claims claims;

  @BeforeEach
  void setUp() {
    database = TestDatabase.fresh();
    claims = new Claims(database);
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  /** Rows left for a turn, asked of the table rather than of the store under test. */
  private long rowsFor(AgentId agentId, String turnId) {
    return JdbcClient.create(database)
        .sql("SELECT count(*) FROM nessy_claim WHERE agent_id = ? AND turn_id = ?")
        .params(agentId.value(), turnId)
        .query(Long.class)
        .single();
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(byte[] value) {
    return new String(value, StandardCharsets.UTF_8);
  }

  @Test
  void what_goes_in_comes_back() {
    claims.put(HOUSE, TurnId.of("turn-1"), "asked", bytes("the question"));

    assertThat(claims.get(HOUSE, TurnId.of("turn-1"), "asked"))
        .isPresent()
        .get()
        .extracting(ClaimsTest::text)
        .isEqualTo("the question");
  }

  @Test
  @DisplayName("claiming the same key twice overwrites, because a re-driven turn does exactly that")
  void a_claim_can_be_written_again() {
    claims.put(HOUSE, TurnId.of("turn-1"), "asked", bytes("first attempt"));

    claims.put(HOUSE, TurnId.of("turn-1"), "asked", bytes("after a crash"));

    assertThat(claims.get(HOUSE, TurnId.of("turn-1"), "asked"))
        .isPresent()
        .get()
        .extracting(ClaimsTest::text)
        .isEqualTo("after a crash");
  }

  @Test
  void one_turn_cannot_see_another_turn_s_claims() {
    claims.put(HOUSE, TurnId.of("turn-1"), "asked", bytes("mine"));

    assertThat(claims.get(HOUSE, TurnId.of("turn-2"), "asked")).isEmpty();
  }

  @Test
  @DisplayName("ending a turn sweeps everything under it, including what nothing referenced")
  void deleting_a_turn_takes_orphans_too() {
    claims.put(HOUSE, TurnId.of("turn-1"), "asked", bytes("the question"));
    claims.put(HOUSE, TurnId.of("turn-1"), "result-c1", bytes("an answer"));
    // Written just before a notional crash: in the kind, named by no state anywhere.
    claims.put(HOUSE, TurnId.of("turn-1"), "orphan", bytes("nobody remembers me"));

    claims.deleteTurn(HOUSE, TurnId.of("turn-1"));

    assertThat(claims.get(HOUSE, TurnId.of("turn-1"), "asked")).isEmpty();
    assertThat(claims.get(HOUSE, TurnId.of("turn-1"), "result-c1")).isEmpty();
    assertThat(claims.get(HOUSE, TurnId.of("turn-1"), "orphan")).isEmpty();
    assertThat(rowsFor(HOUSE, "turn-1")).isZero();
  }

  @Test
  void ending_a_turn_leaves_other_turns_alone() {
    claims.put(HOUSE, TurnId.of("turn-1"), "asked", bytes("mine"));
    claims.put(HOUSE, TurnId.of("turn-2"), "asked", bytes("theirs"));

    claims.deleteTurn(HOUSE, TurnId.of("turn-1"));

    assertThat(claims.get(HOUSE, TurnId.of("turn-2"), "asked")).isPresent();
  }

  @Test
  void ending_a_turn_that_claimed_nothing_is_not_an_error() {
    claims.deleteTurn(HOUSE, TurnId.of("turn-never-ran"));

    assertThat(claims.get(HOUSE, TurnId.of("turn-never-ran"), "anything")).isEmpty();
  }
}
