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
package org.jwcarman.nessy.engine.jdbc;

import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.lock.LockKind;
import org.jwcarman.nessy.backend.lock.Locks;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A {@link Locks} whose exclusion is a Postgres row lock, held for exactly one transaction.
 *
 * <p>A lease is approximate: its row says who holds it and until when, because the process that
 * took it can die without telling anyone, and a caller checking that row is trusting a fact that
 * may already be stale. A row lock has none of that trouble. {@code SELECT ... FOR UPDATE} is held
 * by a transaction and released by the database the moment that transaction ends -- committed,
 * rolled back, or its connection simply dropped -- so there is no stale holder to fence against and
 * nothing for a time-to-live to bound. {@code nessy_lock} therefore has neither a {@code holder}
 * column nor an {@code expires_at}: a row lock IS the holder, for as long as it is one.
 *
 * <p><b>{@link #withLock} absorbs the transaction.</b> There is no separate seam for one: taking
 * the lock, running the work, and committing all happen inside the one transaction this class
 * opens. That is deliberate -- an agent lock guards a step because the step's writes must be atomic
 * with holding it, so the lock cannot be a thing a caller wraps a transaction around. It must BE
 * the transaction.
 *
 * <p><b>Why two constructors.</b> The correctness of the lock depends on one setting: {@link
 * TransactionDefinition#PROPAGATION_REQUIRED}. The row lock and the work it guards -- an append, a
 * payload write, an outbox insert -- must commit as one transaction, or the lock is released before
 * the work it was guarding is durable and it will have guarded nothing. A {@code
 * TransactionTemplate} handed in by a caller could carry any propagation, isolation or timeout it
 * likes -- a template built with {@code REQUIRES_NEW}, for instance, would silently put the lock in
 * a transaction separate from the work, and nothing would fail, and nothing would be excluded. So
 * this class does not accept a template. It accepts a {@link PlatformTransactionManager} -- which
 * every Spring Boot application with a {@link DataSource} already has one of, auto-configured --
 * and builds its own template around it with the one propagation that keeps the lock and the work
 * in the same transaction, so no caller can misconfigure the setting that matters.
 *
 * <p>The single-argument constructor is for a caller with no manager to hand in -- a plain,
 * non-Spring application -- and mints a {@link JdbcTransactionManager} of its own. That is the same
 * thing {@code DefaultQueuedHarnessFactory} used to do for its own transactions, which this design
 * criticised: there, a manager already existed in the caller's application and was being ignored in
 * favor of a second one. Here there is no manager to ignore -- a non-Spring caller has none to
 * coordinate with -- so minting one is not a mistake repeated, it is the only option.
 *
 * <p><b>A sharp edge.</b> {@code PROPAGATION_REQUIRED} joins whichever transaction is already open
 * on the thread. An application that wraps a call into this class inside its own long-running
 * {@code @Transactional} method therefore holds the agent lock until THAT method's commit, which
 * can be far longer than the step this class means to guard, and breaks the invariant that nothing
 * holds an agent lock across anything slow. That is the calling application's transaction and its
 * choice to make; this class cannot forbid it without {@code REQUIRES_NEW}, which is the
 * propagation ruled out above for a worse reason. Anyone who finds a queue of waiters behind a slow
 * request handler should look here first.
 *
 * <p><b>Ensuring the row cannot be allowed to wait.</b> {@code INSERT ... ON CONFLICT DO NOTHING}
 * has no {@code NOWAIT} form: when a rival transaction has already inserted the same key and not
 * yet committed, Postgres makes a second insert of that key wait for the rival to commit or roll
 * back before it can even decide whether there is a conflict -- measured against Postgres 18. Doing
 * the ensure inside the same transaction as {@code FOR UPDATE [NOWAIT]}, as the row lock and the
 * work it guards must be, would therefore make {@link #tryWithLock} wait on a brand-new key exactly
 * when a caller is relying on it never waiting: the first contention on an agent's first lock of a
 * kind. So the ensure runs in a transaction of its own, with {@code REQUIRES_NEW} rather than the
 * {@code REQUIRED} used everywhere else in this class -- deliberately, and only here: {@code
 * REQUIRES_NEW} on the LOCK-AND-WORK transaction would separate the lock from the work it guards,
 * which is the mistake this class exists to prevent; on the ensure it does the opposite, because
 * the ensure is idempotent bookkeeping with nothing of the caller's in it and nothing to keep
 * atomic with anything else -- a row with no columns but the key. Committing it immediately, on its
 * own, before the lock-and-work transaction opens means the row is never left uncommitted for the
 * length of a turn: a rival's insert can still be made to wait, but only for as long as this
 * one-statement transaction takes to commit, never for as long as the work being guarded takes to
 * run. This holds regardless of whether the caller already has an ambient transaction open --
 * {@code REQUIRES_NEW} suspends it for the ensure and resumes it afterward, so a caller nested
 * inside its own {@code @Transactional} method gets the same guarantee as one with no ambient
 * transaction at all.
 */
public final class JdbcRowLocks implements Locks {

  private static final String LOCK_NOT_AVAILABLE_SQLSTATE = "55P03";

  private static final String ENSURE =
      """
      INSERT INTO nessy_lock (kind, agent_type, agent_id)
      VALUES (?, ?, ?)
          ON CONFLICT DO NOTHING
      """;

  private static final String LOCK =
      """
      SELECT 1
        FROM nessy_lock
       WHERE kind = ? AND agent_type = ? AND agent_id = ?
         FOR UPDATE
      """;

  private static final String TRY_LOCK =
      """
      SELECT 1
        FROM nessy_lock
       WHERE kind = ? AND agent_type = ? AND agent_id = ?
         FOR UPDATE NOWAIT
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final TransactionTemplate ensureTransaction;

  /**
   * For a caller that already coordinates its own transactions -- every Spring Boot application
   * with a {@link DataSource} auto-configures one.
   */
  public JdbcRowLocks(DataSource dataSource, PlatformTransactionManager transactions) {
    this.jdbc =
        JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    Objects.requireNonNull(transactions, "transactions must not be null");
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    this.transactions = template;
    TransactionTemplate ensureTemplate = new TransactionTemplate(transactions);
    ensureTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ensureTransaction = ensureTemplate;
  }

  /**
   * For a plain, non-Spring caller with no {@link PlatformTransactionManager} of its own -- mints a
   * {@link JdbcTransactionManager} over {@code dataSource} to coordinate with.
   */
  public JdbcRowLocks(DataSource dataSource) {
    this(dataSource, new JdbcTransactionManager(dataSource));
  }

  @Override
  public <T> Attempt<T> tryWithLock(
      LockKind kind, AgentType type, AgentId agent, Supplier<T> work) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(work, "work must not be null");
    ensureTransaction.executeWithoutResult(_ -> ensure(kind, type, agent));
    try {
      T result =
          transactions.execute(
              _ -> {
                take(TRY_LOCK, kind, type, agent);
                return work.get();
              });
      return new Attempt.Ran<>(result);
    } catch (DataAccessException e) {
      if (isLockNotAvailable(e)) {
        return new Attempt.Ignored<>();
      }
      throw e;
    }
  }

  @Override
  public <T> T withLock(LockKind kind, AgentType type, AgentId agent, Supplier<T> work) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(work, "work must not be null");
    ensureTransaction.executeWithoutResult(_ -> ensure(kind, type, agent));
    return transactions.execute(
        _ -> {
          take(LOCK, kind, type, agent);
          return work.get();
        });
  }

  private void ensure(LockKind kind, AgentType type, AgentId agent) {
    jdbc.sql(ENSURE).params(kind.value(), type.value(), agent.value()).update();
  }

  private void take(String sql, LockKind kind, AgentType type, AgentId agent) {
    jdbc.sql(sql).params(kind.value(), type.value(), agent.value()).query(Integer.class).single();
  }

  /**
   * Whether {@code e} is a refused {@code FOR UPDATE NOWAIT} rather than a real failure. SQLSTATE
   * {@value #LOCK_NOT_AVAILABLE_SQLSTATE} ({@code lock_not_available}) is exactly what Postgres
   * raises for that refusal and nothing else, so it is the discriminator -- everything else
   * propagates, deliberately uncaught.
   */
  private static boolean isLockNotAvailable(Throwable e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException
          && LOCK_NOT_AVAILABLE_SQLSTATE.equals(sqlException.getSQLState())) {
        return true;
      }
    }
    return false;
  }
}
