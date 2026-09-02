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

import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.TurnId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Content a turn must keep for its own duration and no longer.
 *
 * <p>What a tool was asked and what it answered are CONTENT — the size of whatever the tool decided
 * to hand back. Keeping them on a turn's own document would make that document grow with what its
 * tools do, which is the one thing the document's shape is for. They cannot live in the transcript
 * either: an exchange is written whole, so for exactly the window a call is in flight the
 * transcript is designed not to hold it.
 *
 * <p><b>Engine-internal.</b> Nothing outside the engine reads a claim, so this is not an extension
 * point and there is no interface to implement — the engine needs it, so the engine provides it.
 * Hand the engine a {@link DataSource} and its bookkeeping is durable; hand it none and the engine
 * makes an in-memory one.
 *
 * <p><b>Deleted by turn, not by key.</b> Ending a turn is one statement over {@code (agent_id,
 * turn_id)}, which matters for more than tidiness: a claim written just before a crash, before the
 * state naming it was persisted, is an ORPHAN no key list contains. Deleting by turn sweeps it
 * anyway, because it is in the turn.
 */
final class Claims {

  private static final String UPSERT_DELETE =
      "DELETE FROM nessy_claim WHERE agent_id = ? AND turn_id = ? AND claim_key = ?";
  private static final String INSERT =
      "INSERT INTO nessy_claim (agent_id, turn_id, claim_key, payload) VALUES (?, ?, ?, ?)";
  private static final String SELECT =
      "SELECT payload FROM nessy_claim WHERE agent_id = ? AND turn_id = ? AND claim_key = ?";
  private static final String DELETE_TURN =
      "DELETE FROM nessy_claim WHERE agent_id = ? AND turn_id = ?";

  private final JdbcClient jdbc;

  Claims(DataSource dataSource) {
    this.jdbc =
        JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
  }

  /**
   * Writes, overwriting whatever was there.
   *
   * <p>Overwriting matters: a turn that re-drives after a crash claims the same keys again, and a
   * write that insisted the key was new would turn an ordinary recovery into a dead actor.
   *
   * <p>Delete-then-insert rather than an upsert, because {@code ON CONFLICT} and {@code MERGE} are
   * vendor syntax and one DDL and one set of statements have to serve every database we support. A
   * turn is the only writer for its own claims, so there is no race for the two statements to lose.
   */
  void put(AgentId agentId, TurnId turnId, String key, byte[] value) {
    jdbc.sql(UPSERT_DELETE).params(agentId.value(), turnId.value(), key).update();
    jdbc.sql(INSERT).params(agentId.value(), turnId.value(), key, value).update();
  }

  Optional<byte[]> get(AgentId agentId, TurnId turnId, String key) {
    return jdbc.sql(SELECT)
        .params(agentId.value(), turnId.value(), key)
        .query(byte[].class)
        .optional();
  }

  /** The turn ended, so its claims end — including any orphan no state ever referenced. */
  void deleteTurn(AgentId agentId, TurnId turnId) {
    jdbc.sql(DELETE_TURN).params(agentId.value(), turnId.value()).update();
  }
}
