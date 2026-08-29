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
package org.jwcarman.nessy.examples.watchman;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jwcarman.nessy.engine.AgentState;
import org.jwcarman.nessy.engine.Phase;
import org.jwcarman.nessy.engine.StateSerializer;
import org.jwcarman.nessy.engine.ToolCallRecord;

/**
 * The approvals page's read side.
 *
 * <p><b>The choice, and why.</b> Three options were open. <i>Ask the actors</i> — scatter-gather
 * across every live agent; correct, but it makes the page's latency a function of actor health and
 * it can only see agents that are in memory. <i>Write a projection</i> — a table the agent updates
 * when a call parks; fast to read, and a second write that can drift from the state it describes.
 * <i>Read the write model</i>, which is what this does.
 *
 * <p>It works here because of a property the design already had: the agent persists everything the
 * page needs — what was asked, the exact command line, when it was asked, and whether a human has
 * answered — so "what is pending?" is a pure function of state that is already on disk. There is no
 * second write, so there is nothing to drift. And it reuses {@link StateSerializer}, so the page
 * and the agent literally cannot disagree about the format.
 *
 * <p><b>What it costs.</b> A full scan of {@code durable_state} plus a deserialise per row, with no
 * index on "has a pending approval" because that predicate lives in Java rather than in SQL. For
 * one watchman on one box that is free. For a thousand agents it is not, and the answer then is a
 * real projection — at which point the drift problem comes back and has to be managed.
 *
 * <p>It also required two fields to be persisted that the pure actor design would not have needed:
 * {@code askedAt} and {@code decision}. The read side reached back into the write model, which is
 * worth noticing as a general force rather than an accident of this page.
 */
public final class PendingApprovals {

  /** One waiting approval, as the page needs it. */
  public record Row(
      String agentId, String callId, String tool, String action, Instant askedAt, String dwell) {}

  private static final String QUERY =
      "SELECT persistence_id, state_payload FROM durable_state"
          + " WHERE persistence_id LIKE 'Watchman|%'";

  private final DataSource dataSource;
  private final StateSerializer codec = new StateSerializer();
  private final java.time.Clock clock;

  public PendingApprovals(DataSource dataSource, java.time.Clock clock) {
    this.dataSource = dataSource;
    this.clock = clock;
  }

  /** Everything waiting on a human, longest wait first. */
  public List<Row> pending() {
    List<Row> rows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(QUERY);
        ResultSet results = statement.executeQuery()) {
      while (results.next()) {
        String agentId = agentId(results.getString(1));
        Object state = codec.fromBinary(results.getBytes(2), StateSerializer.AGENT_STATE_V2);
        if (state instanceof AgentState agent
            && agent.phase() instanceof Phase.WorkingTools working) {
          working.calls().stream()
              .filter(call -> WatchmanTools.needsApproval(call.tool()))
              .filter(call -> !call.decided() && !call.settled())
              .forEach(call -> rows.add(row(agentId, call)));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not read pending approvals", e);
    }
    rows.sort((a, b) -> a.askedAt().compareTo(b.askedAt()));
    return List.copyOf(rows);
  }

  private Row row(String agentId, ToolCallRecord call) {
    return new Row(
        agentId,
        call.id(),
        call.tool(),
        call.action(),
        call.askedAt(),
        dwell(Duration.between(call.askedAt(), clock.instant())));
  }

  private static String agentId(String persistenceId) {
    return persistenceId.substring(persistenceId.indexOf('|') + 1);
  }

  /** How long, in the coarsest unit that is still true. Days matter here; seconds do not. */
  static String dwell(Duration waited) {
    if (waited.isNegative()) {
      return "0m";
    }
    if (waited.toDays() > 0) {
      return waited.toDays() + "d " + waited.toHoursPart() + "h";
    }
    if (waited.toHours() > 0) {
      return waited.toHours() + "h " + waited.toMinutesPart() + "m";
    }
    return waited.toMinutes() + "m";
  }
}
