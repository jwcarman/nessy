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

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything one agent durably is, and the lock that serializes it.
 *
 * <p><b>The lock IS the mailbox.</b> An actor serialized an agent by owning a queue; a row does it
 * by refusing to be read twice at once. The difference that matters is what happens when the
 * process holding it dies: a mailbox and its unprocessed messages go with it, and a lock is simply
 * released.
 *
 * <p><b>Must be called inside a transaction.</b> {@code SELECT ... FOR UPDATE} holds only until
 * commit, so a caller without one holds nothing. {@link Transition} is the intended caller and owns
 * that transaction.
 */
final class AgentStore {

  private static final String LOCK =
      "SELECT state FROM nessy_agent WHERE agent_type = ? AND agent_id = ? FOR UPDATE";
  private static final String PEEK =
      "SELECT state FROM nessy_agent WHERE agent_type = ? AND agent_id = ?";
  private static final String INSERT =
      "INSERT INTO nessy_agent (agent_type, agent_id, version, state, last_touched_at)"
          + " VALUES (?, ?, ?, ?, ?)";
  private static final String UPDATE =
      "UPDATE nessy_agent SET version = version + 1, state = ?, last_touched_at = ?"
          + " WHERE agent_type = ? AND agent_id = ?";
  private static final String TOUCH =
      "UPDATE nessy_agent SET last_touched_at = ? WHERE agent_type = ? AND agent_id = ?";
  private static final String DELETE =
      "DELETE FROM nessy_agent WHERE agent_type = ? AND agent_id = ?";
  private static final String STALLED =
      "SELECT agent_id, state FROM nessy_agent"
          + " WHERE agent_type = ? AND last_touched_at < ? ORDER BY last_touched_at";

  private static final String AGENT_TYPE_NOT_NULL = "agentType must not be null";
  private static final String AGENT_ID_NOT_NULL = "agentId must not be null";

  private final JdbcClient jdbc;
  private final Codec<AgentState> codec;
  private final TransactionTemplate savepointTransaction;

  AgentStore(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.jdbc = JdbcClient.create(dataSource);
    this.codec = JsonCodec.of(EngineMapper.INSTANCE, AgentState.class);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    // Off by default -- without this, PROPAGATION_NESTED throws
    // NestedTransactionNotSupportedException
    // rather than silently degrading, but create() needs the savepoint it enables, not the throw.
    transactionManager.setNestedTransactionAllowed(true);
    this.savepointTransaction = new TransactionTemplate(transactionManager);
    this.savepointTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  }

  /**
   * Takes this agent's row and returns what it says, creating it idle if this is the first anyone
   * has heard of it.
   *
   * <p>Insert-then-lock rather than an upsert: {@code ON CONFLICT} is PostgreSQL's spelling and
   * {@code MERGE} is a third, and the schema is held to ANSI by SchemasTest. Catching the duplicate
   * is portable, and the race it handles -- two nodes meeting a brand new agent at once -- is real.
   */
  AgentState lockAndLoad(AgentType agentType, AgentId agentId) {
    Objects.requireNonNull(agentType, AGENT_TYPE_NOT_NULL);
    Objects.requireNonNull(agentId, AGENT_ID_NOT_NULL);
    Optional<byte[]> held = read(agentType, agentId);
    if (held.isPresent()) {
      return codec.decode(held.get());
    }
    create(agentType, agentId);
    return read(agentType, agentId).map(codec::decode).orElseGet(AgentState::idle);
  }

  /**
   * What this agent's row says right now, taking no lock and creating nothing.
   *
   * <p>For a reader that has no business writing: a lock it does not need is exclusivity it does
   * not want, and {@link #lockAndLoad} conjuring an idle row for a stranger is exactly wrong for a
   * caller that only wants to know whether anyone is home. Empty here means "nobody has ever heard
   * of this agent" -- distinct from {@code AgentState.idle()}, which {@link #lockAndLoad} hands
   * back for the SAME question when it is also the one bringing the row into existence.
   */
  Optional<AgentState> peek(AgentType agentType, AgentId agentId) {
    Objects.requireNonNull(agentType, AGENT_TYPE_NOT_NULL);
    Objects.requireNonNull(agentId, AGENT_ID_NOT_NULL);
    return jdbc.sql(PEEK)
        .param(agentType.name())
        .param(agentId.value())
        .query(byte[].class)
        .optional()
        .map(codec::decode);
  }

  void save(AgentType agentType, AgentId agentId, AgentState state) {
    Objects.requireNonNull(agentType, AGENT_TYPE_NOT_NULL);
    Objects.requireNonNull(agentId, AGENT_ID_NOT_NULL);
    Objects.requireNonNull(state, "state must not be null");
    if (update(agentType, agentId, state) == 0) {
      create(agentType, agentId);
      update(agentType, agentId, state);
    }
  }

  /**
   * Says "this agent was looked at just now" and nothing else -- no state, no version bump.
   *
   * <p>F2: {@link #stalled} reads liveness off {@code last_touched_at}, which only {@link #save}
   * moves -- and a recovery that decides an agent needs nothing has, by construction, nothing to
   * save. Without this the agent stays in the sweep's result set forever: it is re-examined on
   * every pass, and, being ordered oldest-first, it stands in front of the genuinely stalled agents
   * a bounded pass is meant to reach. Touching says what actually happened -- somebody checked, and
   * this agent is fine -- rather than persisting a state nothing changed just to move a timestamp.
   */
  void touch(AgentType agentType, AgentId agentId) {
    Objects.requireNonNull(agentType, AGENT_TYPE_NOT_NULL);
    Objects.requireNonNull(agentId, AGENT_ID_NOT_NULL);
    jdbc.sql(TOUCH).param(Instant.now()).param(agentType.name()).param(agentId.value()).update();
  }

  void delete(AgentType agentType, AgentId agentId) {
    Objects.requireNonNull(agentType, AGENT_TYPE_NOT_NULL);
    Objects.requireNonNull(agentId, AGENT_ID_NOT_NULL);
    jdbc.sql(DELETE).param(agentType.name()).param(agentId.value()).update();
  }

  /**
   * Agents that are mid-turn and have not moved since {@code before}.
   *
   * <p>Busy is read from the STATE rather than from a column of its own. A second place to record
   * it is a second place for it to disagree with {@link AgentState#busy()}, and the reaper acting
   * on the wrong one would either re-drive live agents or never wake dead ones.
   */
  List<AgentId> stalled(AgentType agentType, Instant before, int limit) {
    Objects.requireNonNull(agentType, AGENT_TYPE_NOT_NULL);
    return jdbc
        .sql(STALLED)
        .param(agentType.name())
        .param(before)
        .query((rs, row) -> new Stalled(rs.getString("agent_id"), rs.getBytes("state")))
        .list()
        .stream()
        .filter(candidate -> codec.decode(candidate.state()).busy())
        .limit(limit)
        .map(candidate -> AgentId.of(candidate.agentId()))
        .toList();
  }

  private record Stalled(String agentId, byte[] state) {}

  private int update(AgentType agentType, AgentId agentId, AgentState state) {
    return jdbc.sql(UPDATE)
        .param(codec.encode(state))
        .param(Instant.now())
        .param(agentType.name())
        .param(agentId.value())
        .update();
  }

  private Optional<byte[]> read(AgentType agentType, AgentId agentId) {
    return jdbc.sql(LOCK)
        .param(agentType.name())
        .param(agentId.value())
        .query(byte[].class)
        .optional();
  }

  /**
   * Inserts the idle row behind a savepoint, not a second transaction.
   *
   * <p>PostgreSQL aborts the WHOLE transaction on any SQL error, and {@link JdbcClient} sets no
   * savepoint of its own -- so an insert that loses the duplicate-key race here would poison every
   * statement the caller runs afterward in ITS transaction, on ITS connection, for the rest of it.
   *
   * <p><b>A savepoint, not {@code PROPAGATION_REQUIRES_NEW}.</b> A second transaction needs a
   * SECOND connection, held open at the same time as the caller's first one. Under test that costs
   * nothing -- {@code TestDatabase.fresh()} runs on an unpooled {@code SimpleDriverDataSource} that
   * hands out a fresh connection to whoever asks. In production, behind Spring Boot's pooled and
   * BOUNDED HikariCP, it is a deadlock: N concurrent first-touches of brand new agents each hold
   * connection 1 and each block waiting on connection 2, and with a pool smaller than N every one
   * of them waits out the connection-acquisition timeout. {@code PROPAGATION_NESTED} isolates the
   * failed insert with a JDBC savepoint on the SAME connection the caller is already holding --
   * {@code ROLLBACK TO SAVEPOINT} clears PostgreSQL's poisoned-transaction state without aborting
   * the caller's transaction, and no second connection is ever asked for. Do not "simplify" this
   * back to a second transaction; that is the deadlock, not a refactor of it.
   *
   * <p>Two shapes of "someone beat us to it" reach here depending on timing, and both mean the row
   * we wanted now exists: {@link DuplicateKeyException} when the other insert had already
   * committed, and a {@link TransientDataAccessException} (lock-wait timeout, e.g. {@code
   * CannotAcquireLockException}) when it was still in flight and we gave up waiting on its lock.
   */
  private void create(AgentType agentType, AgentId agentId) {
    try {
      savepointTransaction.executeWithoutResult(
          status ->
              jdbc.sql(INSERT)
                  .param(agentType.name())
                  .param(agentId.value())
                  .param(0L)
                  .param(codec.encode(AgentState.idle()))
                  .param(Instant.now())
                  .update());
    } catch (DuplicateKeyException | TransientDataAccessException _) {
      // Another node met this agent first, or is still inserting it. Its row is the one we want.
    }
  }
}
