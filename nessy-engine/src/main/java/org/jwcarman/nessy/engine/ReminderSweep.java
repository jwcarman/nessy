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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Notices deadlines that have passed, and tells the agents waiting on them.
 *
 * <p>A durable deadline does not fire itself. Something has to look — and looking is all this does:
 * it reads what is due, sends each one to the agent's logical address, and moves the row forward.
 *
 * <p><b>It bumps rather than deletes.</b> The OWNER deletes a reminder when its call settles,
 * because the owner is the only thing that knows the call settled. A row whose agent never settles
 * it would otherwise re-fire on every tick forever; moving it forward turns that into backoff.
 *
 * <p><b>Its count is the point, not decoration.</b> A sweep that fires nothing and a sweep that has
 * died look identical from outside, so every pass reports what it did.
 */
final class ReminderSweep {

  private static final Logger LOG = LoggerFactory.getLogger(ReminderSweep.class);

  /** How far a fired reminder moves out, so a call nobody settles backs off instead of looping. */
  static final Duration BACKOFF = Duration.ofMinutes(1);

  /**
   * The most reminders one pass will fire.
   *
   * <p>A bound rather than a throttle: whatever is left is still at the front of the index next
   * tick, so a backlog drains over several passes instead of one pass doing unbounded work.
   */
  static final int BATCH = 100;

  private final Reminders reminders;
  private final Clock clock;
  private final BiConsumer<Coordinates, NessyMessage.Expired> deliver;

  /** Which agent, and which of its calls — the coordinates a reply token already carries. */
  record Coordinates(String agentType, String agentId, String callId) {}

  ReminderSweep(
      Reminders reminders, Clock clock, BiConsumer<Coordinates, NessyMessage.Expired> deliver) {
    this.reminders = Objects.requireNonNull(reminders, "reminders must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.deliver = Objects.requireNonNull(deliver, "deliver must not be null");
  }

  /**
   * One pass.
   *
   * @return how many reminders fired, which is the number worth a metric
   */
  int sweep() {
    List<Reminders.Reminder> due = reminders.due(clock.instant(), BATCH);
    for (Reminders.Reminder reminder : due) {
      Coordinates where = decode(reminder.payload());
      deliver.accept(where, new NessyMessage.Expired(where.callId(), Map.of()));
      // Forward, not gone: the agent deletes it when the call settles.
      reminders.remind(reminder.key(), clock.instant().plus(BACKOFF), reminder.payload());
    }
    if (!due.isEmpty()) {
      LOG.info("reminder sweep fired {} expired deadline(s)", due.size());
    }
    return due.size();
  }

  /** The key a call's deadline is filed under. Deterministic, so settling can cancel it. */
  static String keyFor(String agentType, String agentId, String callId) {
    return agentType + "/" + agentId + "/" + callId;
  }

  /**
   * An ADDRESS, never a continuation.
   *
   * <p>Three fields separated by slashes rather than JSON: the payload says where to send a message
   * and nothing else, and keeping it that small is what stops anyone serialising behaviour into it
   * later.
   */
  static byte[] encode(Coordinates where) {
    return (where.agentType() + "/" + where.agentId() + "/" + where.callId())
        .getBytes(StandardCharsets.UTF_8);
  }

  static Coordinates decode(byte[] payload) {
    String[] parts = new String(payload, StandardCharsets.UTF_8).split("/", 3);
    if (parts.length != 3) {
      throw new IllegalArgumentException("a reminder payload names an agent and a call");
    }
    return new Coordinates(parts[0], parts[1], parts[2]);
  }
}
