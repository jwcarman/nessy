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
package org.jwcarman.nessy.examples.watchman.pekko;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A one-off: move transcripts out of rows written before the transcript moved out of the state.
 *
 * <p>It exists because of the shape of the problem, not because of this port. A {@code
 * DurableStateBehavior} rewrites its document in place, so there is no event log to replay: when a
 * field leaves the state, the only copy of the old data is in rows nobody will ever write again.
 * {@link StateSerializer} tolerating unknown fields makes those rows LOAD; it does not make the
 * data they carry survive. Anything worth keeping has to be moved deliberately, once, by something
 * like this.
 *
 * <p>Run against the soak's own database, it lifts every embedded turn into {@code nessy_journal}
 * so the watchman's history is not lost to a refactor.
 */
public final class LegacyTranscriptMigration {

  private static final Logger LOG = LoggerFactory.getLogger(LegacyTranscriptMigration.class);

  private final DataSource dataSource;
  private final Transcript transcript;

  public LegacyTranscriptMigration(DataSource dataSource, Transcript transcript) {
    this.dataSource = dataSource;
    this.transcript = transcript;
  }

  /**
   * @return the agent ids whose embedded transcript was moved, and how many turns each carried
   */
  public List<String> run() {
    List<String> moved = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT persistence_id, state_payload FROM durable_state"
                    + " WHERE persistence_id LIKE 'Watchman|%'");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        String agentId = rows.getString(1).substring(rows.getString(1).indexOf('|') + 1);
        JsonNode embedded = StateSerializer.MAPPER.readTree(rows.getBytes(2)).path("transcript");
        if (!embedded.isArray() || embedded.isEmpty()) {
          continue;
        }
        if (!transcript.entries(agentId).isEmpty()) {
          LOG.info("[watchman] {} already has a journal transcript; leaving it alone", agentId);
          continue;
        }
        for (JsonNode turn : embedded) {
          transcript.append(agentId, StateSerializer.MAPPER.treeToValue(turn, Turn.class));
        }
        LOG.info("[watchman] moved {} turns for {} into nessy_journal", embedded.size(), agentId);
        moved.add(agentId);
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not migrate legacy transcripts", e);
    }
    return List.copyOf(moved);
  }
}
