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
package org.jwcarman.nessy.spike.pekko;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * THROWAWAY SPIKE. The driver obligation, owned by us.
 *
 * <p>This replaces {@code rememberEntities}, and it is worth being precise about what it replaces
 * and what it improves on.
 *
 * <p>{@code rememberEntities} resurrects EVERY entity it has ever been told to remember, on every
 * startup. Round 1 measured that as a genuine liability: the logs show agents from earlier test
 * runs waking up alongside the one under test, and after a year "start the app" means "reanimate
 * every agent that ever existed". Retiring finished agents from the remembered set was listed as
 * undesigned work Pekko does not do for us.
 *
 * <p>This is a query. We own the schema, so we can ask the only question that matters — <i>which
 * turns are not finished?</i> — and wake exactly those. Idle agents stay asleep at no cost, and the
 * retire-from-the-set problem never exists, because there is no set: there is a predicate over
 * state. Waking an agent is one {@link AgentActor.Wake}, whose only job is to bring the actor into
 * memory so that {@code RecoveryCompleted} runs and its own resume rule re-fires whatever it owes.
 *
 * <p>Deserialising with {@link SpikeStateSerializer} rather than pattern-matching the JSON is
 * deliberate: the sweep asks the same codec everything else asks, so a state shape it does not
 * understand fails loudly here rather than being silently classified as finished. A silent
 * mis-classification would be exactly the bug class this sweep exists to prevent.
 */
@FunctionalInterface
public interface SpikeSweep {

  /** Agent ids whose turn is not finished, and which therefore need an actor. */
  List<String> unfinishedAgents();

  /** For the in-memory tier, where a restart loses everything anyway. */
  static SpikeSweep none() {
    return List::of;
  }

  /** For Postgres: read the durable-state table we own and ask which turns are unfinished. */
  static SpikeSweep overPostgres(String url, String user, String password) {
    return new JdbcSweep(url, user, password);
  }

  /** The JDBC implementation; plain JDBC because it is our schema and our question. */
  final class JdbcSweep implements SpikeSweep {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcSweep.class);

    /**
     * The persistence id Pekko writes is {@code SpikeTurn|<agentId>}; the agent id is what follows
     * the separator.
     */
    private static final String QUERY =
        "SELECT persistence_id, state_payload FROM durable_state WHERE persistence_id LIKE 'SpikeTurn|%'";

    private final String url;
    private final String user;
    private final String password;
    private final SpikeStateSerializer codec = new SpikeStateSerializer();

    private JdbcSweep(String url, String user, String password) {
      this.url = url;
      this.user = user;
      this.password = password;
    }

    @Override
    public List<String> unfinishedAgents() {
      List<String> unfinished = new ArrayList<>();
      try (Connection connection = DriverManager.getConnection(url, user, password);
          PreparedStatement statement = connection.prepareStatement(QUERY);
          ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String persistenceId = rows.getString(1);
          Object state = codec.fromBinary(rows.getBytes(2), SpikeStateSerializer.TURN_STATE_V1);
          if (!(state instanceof SpikeTurnState.Idle)) {
            unfinished.add(persistenceId.substring(persistenceId.indexOf('|') + 1));
          }
        }
      } catch (SQLException e) {
        throw new IllegalStateException("the startup sweep could not read durable_state", e);
      }
      LOG.info(
          "[spike] startup sweep found {} unfinished turn(s): {}", unfinished.size(), unfinished);
      return List.copyOf(unfinished);
    }
  }
}
