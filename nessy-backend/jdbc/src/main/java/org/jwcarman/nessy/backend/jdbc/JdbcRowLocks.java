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
package org.jwcarman.nessy.backend.jdbc;

import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.lock.LockKind;
import org.jwcarman.nessy.backend.lock.Locks;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A {@link Locks} whose exclusion is a Postgres advisory lock, held for exactly one transaction.
 *
 * <p>A lease is approximate: its row says who holds it and until when, because the process that
 * took it can die without telling anyone, and a caller checking that row is trusting a fact that
 * may already be stale. {@code pg_advisory_xact_lock} has none of that trouble. It is held by a
 * transaction and released by the database the moment that transaction ends -- committed, rolled
 * back, or its connection simply dropped -- so there is no stale holder to fence against and
 * nothing for a time-to-live to bound. Unlike a row lock, it needs no row to be true of: it is
 * taken against a 64-bit key computed from the {@code (kind, type, agent)} triple, with nothing to
 * insert, ensure, or ever clean up.
 *
 * <p><b>{@link #withLock} absorbs the transaction.</b> There is no separate seam for one: taking
 * the lock, running the work, and committing all happen inside the one transaction this class
 * opens. That is deliberate -- an agent lock guards a step because the step's writes must be atomic
 * with holding it, so the lock cannot be a thing a caller wraps a transaction around. It must BE
 * the transaction.
 *
 * <p><b>Why two constructors.</b> The correctness of the lock depends on one setting: {@link
 * TransactionDefinition#PROPAGATION_REQUIRED}. The advisory lock and the work it guards -- an
 * append, a payload write, an outbox insert -- must commit as one transaction, or the lock is
 * released before the work it was guarding is durable and it will have guarded nothing. A {@code
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
 * choice to make; this class cannot forbid it without {@code REQUIRES_NEW}, which would separate
 * the lock from the work it guards -- the very thing {@link #withLock} exists to prevent. Anyone
 * who finds a queue of waiters behind a slow request handler should look here first.
 *
 * <p><b>The key is a hash, and hashes can collide.</b> {@code pg_advisory_xact_lock} takes a single
 * 64-bit integer, not a triple, so the {@code (kind, type, agent)} key is hashed down to one with
 * {@code hashtextextended}. Two different triples can therefore land on the same 64-bit value and
 * serialise against each other even though nothing about them is actually related. That is
 * acceptable because a collision can only ever make two unrelated agents take turns behind the same
 * lock -- it can never let two callers who share a real key both hold it, since equal keys always
 * hash to the same value. A collision is a performance event, not a correctness one, and at 64 bits
 * it is also a rare one.
 *
 * <p><b>The key is namespaced.</b> Advisory locks are scoped to a database, not to a schema or an
 * application, so any other application sharing this database and calling {@code
 * pg_advisory_xact_lock} with an unrelated key could, by coincidence, collide with this class's
 * locks. The {@code "nessy:"} prefix folded into the key before it is hashed is what keeps this
 * class's locks in their own namespace rather than sharing the whole database's advisory-lock space
 * by accident.
 *
 * <p><b>Losing the table costs some observability, but less than it first appears.</b> {@code
 * nessy_lock} let an operator enumerate held locks by reading a table -- but it never said WHO held
 * one, since it had no holder column, so that table could only ever answer "is (kind, type, agent)
 * locked," never "by what." {@code pg_locks} answers a narrower question the same way: it lists
 * advisory locks by their hashed {@code objid}/{@code classid}, not by the triple that produced
 * them, so there is no column to read a held lock's agent back out of. What still works is the
 * question run forward instead of backward -- hash the {@code (kind, type, agent)} you already
 * suspect with the same {@code "nessy:" + kind + "/" + type + "/" + agent} key and {@code
 * hashtextextended(key, 0)} this class uses, and look for that value among granted locks in {@code
 * pg_locks}.
 */
public final class JdbcRowLocks implements Locks {

  private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

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
  }

  /**
   * For a plain, non-Spring caller with no {@link PlatformTransactionManager} of its own -- mints a
   * {@link JdbcTransactionManager} over {@code dataSource} to coordinate with.
   */
  public JdbcRowLocks(DataSource dataSource) {
    this(dataSource, new JdbcTransactionManager(dataSource));
  }

  @Override
  public <T> T withLock(LockKind kind, AgentType type, AgentId agent, Supplier<T> work) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(work, "work must not be null");
    return transactions.execute(
        _ -> {
          take(kind, type, agent);
          return work.get();
        });
  }

  private void take(LockKind kind, AgentType type, AgentId agent) {
    String key = "nessy:" + kind.value() + "/" + type.value() + "/" + agent.value();
    // pg_advisory_xact_lock returns void, so this is a query, not an update -- executeUpdate()
    // rejects it with "A result was returned when none was expected." query() requires a non-null
    // extractor result, so the extractor returns a throwaway value rather than the row's content.
    jdbc.sql(LOCK).param(key).query(resultSet -> Boolean.TRUE);
  }
}
