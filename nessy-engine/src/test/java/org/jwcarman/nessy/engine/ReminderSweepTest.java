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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

/**
 * The thing that notices a deadline has passed.
 *
 * <p>No race to win here, which is the point of making a deadline a row: the sweep is a call, the
 * clock is a parameter, and every property below is asserted rather than waited for.
 */
@DisplayName("The reminder sweep")
class ReminderSweepTest {

  private static final Instant NOON = Instant.parse("2026-09-01T12:00:00Z");
  private static final ReminderSweep.Coordinates WHERE =
      new ReminderSweep.Coordinates("chat", "agent-one", "call-1");

  private EmbeddedDatabase database;
  private Reminders reminders;
  private List<ReminderSweep.Coordinates> delivered;
  private ReminderSweep sweep;

  @BeforeEach
  void fresh() {
    database = TestDatabase.fresh();
    reminders = new Reminders(database);
    delivered = new ArrayList<>();
    sweep =
        new ReminderSweep(
            reminders, Clock.fixed(NOON, ZoneOffset.UTC), (where, expired) -> delivered.add(where));
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  private void park(String callId, Instant expiresAt) {
    reminders.remind(WHERE.agentType(), WHERE.agentId(), callId, expiresAt);
  }

  @Test
  void a_sweep_with_nothing_due_tells_nobody() {
    park("call-1", NOON.plusSeconds(60));

    assertThat(sweep.sweep()).isZero();
    assertThat(delivered).isEmpty();
  }

  @Test
  @DisplayName("an expired deadline reaches the agent that parked it")
  void a_due_reminder_is_delivered_to_its_coordinates() {
    park("call-1", NOON.minusSeconds(1));

    assertThat(sweep.sweep()).isEqualTo(1);

    assertThat(delivered).containsExactly(WHERE);
  }

  /**
   * The owner deletes a settled call's reminder. One nobody settles must back off rather than
   * re-fire every tick — otherwise a single stuck call becomes an unbounded stream of messages.
   */
  @Test
  @DisplayName("a fired reminder is bumped forward, not deleted")
  void firing_backs_it_off_rather_than_looping() {
    park("call-1", NOON.minusSeconds(1));

    sweep.sweep();

    assertThat(reminders.find("chat", "agent-one", "call-1").orElseThrow())
        .extracting(Reminders.Reminder::expiresAt)
        .isEqualTo(NOON.plus(ReminderSweep.BACKOFF));
    assertThat(sweep.sweep()).isZero();
  }

  @Test
  void a_cancelled_reminder_never_fires() {
    park("call-1", NOON.minusSeconds(1));
    reminders.cancel("chat", "agent-one", "call-1");

    assertThat(sweep.sweep()).isZero();
    assertThat(delivered).isEmpty();
  }

  @Test
  @DisplayName("many due at once are all delivered, earliest first")
  void a_backlog_drains_in_order() {
    park("call-3", NOON.minusSeconds(1));
    park("call-1", NOON.minusSeconds(3));
    park("call-2", NOON.minusSeconds(2));

    assertThat(sweep.sweep()).isEqualTo(3);

    assertThat(delivered)
        .extracting(ReminderSweep.Coordinates::callId)
        .containsExactly("call-1", "call-2", "call-3");
  }

  @Test
  @DisplayName("the payload carries an address and nothing else")
  void coordinates_round_trip() {
    assertThat(ReminderSweep.decode(ReminderSweep.encode(WHERE))).isEqualTo(WHERE);
  }
}
