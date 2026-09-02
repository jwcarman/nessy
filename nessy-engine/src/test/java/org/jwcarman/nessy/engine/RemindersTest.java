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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

/**
 * A deadline written down.
 *
 * <p>The behaviour that matters is not "a row goes in and comes out" but the three properties the
 * sweep depends on: due means due at or before the instant asked about, earliest comes first, and
 * reminding an existing key MOVES it rather than leaving two.
 */
@DisplayName("A deadline that outlives its actor")
class RemindersTest {

  private static final String TYPE = "watchman";
  private static final String AGENT = "house-12";

  private static final Instant NOON = Instant.parse("2026-09-01T12:00:00Z");

  private EmbeddedDatabase database;
  private Reminders reminders;

  @BeforeEach
  void fresh() {
    database = TestDatabase.fresh();
    reminders = new Reminders(database);
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  @Test
  void a_store_with_nothing_in_it_owes_nothing() {
    assertThat(reminders.due(NOON, 10)).isEmpty();
  }

  @Test
  @DisplayName("a reminder due now is returned; one due later is not")
  void only_what_is_due_comes_back() {
    reminders.remind(TYPE, AGENT, "past", NOON.minusSeconds(1));
    reminders.remind(TYPE, AGENT, "future", NOON.plusSeconds(1));

    assertThat(reminders.due(NOON, 10))
        .extracting(Reminders.Reminder::callId)
        .containsExactly("past");
  }

  /** At-or-before, not strictly-before: a reminder due exactly now has come due. */
  @Test
  void the_boundary_instant_is_due() {
    reminders.remind(TYPE, AGENT, "exactly", NOON);

    assertThat(reminders.due(NOON, 10)).hasSize(1);
  }

  /** The sweep reads from the front and stops, so this ordering is what bounds its cost. */
  @Test
  @DisplayName("earliest first, so a sweep can stop at the first one not yet due")
  void due_returns_them_in_order() {
    reminders.remind(TYPE, AGENT, "third", NOON.minusSeconds(1));
    reminders.remind(TYPE, AGENT, "first", NOON.minusSeconds(3));
    reminders.remind(TYPE, AGENT, "second", NOON.minusSeconds(2));

    assertThat(reminders.due(NOON, 10))
        .extracting(Reminders.Reminder::callId)
        .containsExactly("first", "second", "third");
  }

  @Test
  void due_respects_its_limit() {
    reminders.remind(TYPE, AGENT, "a", NOON.minusSeconds(3));
    reminders.remind(TYPE, AGENT, "b", NOON.minusSeconds(2));

    assertThat(reminders.due(NOON, 1)).hasSize(1);
  }

  /**
   * This is what makes both extending a term and backing a stuck reminder off a single call — and
   * what stops a sweep that bumps from leaving a duplicate behind.
   */
  @Test
  @DisplayName("reminding an existing key moves it rather than duplicating it")
  void remind_is_an_upsert() {
    reminders.remind(TYPE, AGENT, "call-1", NOON.minusSeconds(1));

    reminders.remind(TYPE, AGENT, "call-1", NOON.plusSeconds(60));

    assertThat(reminders.due(NOON, 10)).isEmpty();
    assertThat(reminders.find(TYPE, AGENT, "call-1").orElseThrow().expiresAt())
        .isEqualTo(NOON.plusSeconds(60));
  }

  @Test
  @DisplayName("a restarted actor can read back what it was waiting for")
  void find_returns_the_remaining_term() {
    reminders.remind(TYPE, AGENT, "call-1", NOON.plusSeconds(300));

    Reminders.Reminder found = reminders.find(TYPE, AGENT, "call-1").orElseThrow();

    assertThat(found.expiresAt()).isEqualTo(NOON.plusSeconds(300));
    assertThat(found.agentType()).isEqualTo(TYPE);
    assertThat(found.agentId()).isEqualTo(AGENT);
    assertThat(found.callId()).isEqualTo("call-1");
  }

  @Test
  void cancelling_removes_it() {
    reminders.remind(TYPE, AGENT, "call-1", NOON.minusSeconds(1));

    reminders.cancel(TYPE, AGENT, "call-1");

    assertThat(reminders.due(NOON, 10)).isEmpty();
    assertThat(reminders.find(TYPE, AGENT, "call-1")).isEmpty();
  }

  /** A call can settle by more than one route, and each of them cancels. */
  @Test
  @DisplayName("cancelling something that was never there is silent")
  void cancelling_twice_is_not_an_error() {
    reminders.cancel(TYPE, AGENT, "never-there");

    assertThat(reminders.find(TYPE, AGENT, "never-there")).isEmpty();
  }

  @Test
  void a_limit_below_one_is_refused() {
    assertThatThrownBy(() -> reminders.due(NOON, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Nested
  @DisplayName("whose deadline it is")
  class Identity {

    @Test
    @DisplayName("an agent id containing the old separator is just an id now")
    void two_agents_do_not_share_a_deadline() {
      reminders.remind(TYPE, "acme/user-7", "c1", NOON.plusSeconds(60));
      reminders.remind(TYPE, "acme", "user-7/c1", NOON.plusSeconds(120));

      assertThat(reminders.find(TYPE, "acme/user-7", "c1").orElseThrow().expiresAt())
          .as("a composed key made these one row, so arming one overwrote the other's deadline")
          .isEqualTo(NOON.plusSeconds(60));
      assertThat(reminders.find(TYPE, "acme", "user-7/c1").orElseThrow().expiresAt())
          .isEqualTo(NOON.plusSeconds(120));
    }

    @Test
    void cancelling_one_leaves_the_other_armed() {
      reminders.remind(TYPE, "acme/user-7", "c1", NOON.plusSeconds(60));
      reminders.remind(TYPE, "acme", "user-7/c1", NOON.plusSeconds(120));

      reminders.cancel(TYPE, "acme/user-7", "c1");

      assertThat(reminders.find(TYPE, "acme/user-7", "c1")).isEmpty();
      assertThat(reminders.find(TYPE, "acme", "user-7/c1")).isPresent();
    }

    @Test
    void two_agent_types_do_not_share_a_deadline() {
      reminders.remind("watchman", AGENT, "c1", NOON.plusSeconds(60));
      reminders.remind("chat", AGENT, "c1", NOON.plusSeconds(120));

      assertThat(reminders.due(NOON.plusSeconds(200), 10)).hasSize(2);
    }
  }
}
