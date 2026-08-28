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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The driver obligation: on boot, find rounds that are not finished and bring their actors back.
 *
 * <p>This is what a single-node deployment uses instead of Cluster Sharding's {@code
 * rememberEntities}, and it is strictly better for this application: remember-entities resurrects
 * every entity it has ever been told to remember, whereas this wakes only what is unfinished. An
 * idle watchman stays asleep, and there is no set of remembered ids that grows forever and has to
 * be pruned.
 *
 * <p>Waking is one {@link AgentActor.Wake}, whose only job is to bring the actor into memory so
 * that {@code RecoveryCompleted} fires and the agent's own resume rule re-fires whatever it owes.
 */
public final class StartupSweep {

  private static final Logger LOG = LoggerFactory.getLogger(StartupSweep.class);

  private static final String QUERY =
      "SELECT persistence_id, state_payload, state_serial_manifest FROM durable_state"
          + " WHERE persistence_id LIKE 'Watchman|%'";

  private final DataSource dataSource;
  private final StateSerializer codec = new StateSerializer();

  public StartupSweep(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public List<String> unfinishedAgents() {
    List<String> unfinished = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(QUERY);
        ResultSet results = statement.executeQuery()) {
      while (results.next()) {
        String persistenceId = results.getString(1);
        Object state = codec.fromBinary(results.getBytes(2), results.getString(3));
        if (state instanceof AgentState agent && !(agent.phase() instanceof Phase.Idle)) {
          unfinished.add(persistenceId.substring(persistenceId.indexOf('|') + 1));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("the startup sweep could not read durable_state", e);
    }
    LOG.info(
        "[watchman] startup sweep found {} unfinished round(s): {}", unfinished.size(), unfinished);
    return List.copyOf(unfinished);
  }
}
