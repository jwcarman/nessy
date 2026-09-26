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

package org.jwcarman.nessy.lease;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jwcarman.nessy.backend.lock.Locks;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Locks as leases: rows in {@code nessy_lease}, held for a time and taken over once it passes.
 *
 * <p>The expiry is what makes this work across machines. A holder that finishes releases; a holder
 * that dies leaves its row to expire, after which the next caller takes it over -- which is also
 * why the work must be idempotent, since a holder that is merely slow is indistinguishable from one
 * that died.
 *
 * <p><b>One statement to take.</b> An insert that, on finding the row already there, takes it over
 * only if it has expired -- and says which happened by returning the holder. A caller whose holder
 * comes back is the holder; one whose does not was refused. No read-then-write, so two callers
 * cannot both conclude the lease is free: the row lock inside the {@code INSERT ... ON CONFLICT}
 * serialises them.
 *
 * <p><b>The database's clock.</b> Expiry is compared against {@code now()} in the statement, so
 * holders on different machines need not agree on the time.
 *
 * <p><b>The kind and the time-to-live are settled here, at construction.</b> One of these serves
 * one kind of work, and whoever wires it up is the one who knows how long that work takes. Err
 * long: a lease that expires while its holder is still working lets two run at once, which is the
 * failure this exists to prevent, whereas one that outlives a crash only delays the next attempt.
 */
public class JdbcLeases implements Locks {

  private static final String TAKE =
      """
      INSERT INTO nessy_lease (kind, key, holder, expires_at)
      VALUES (?, ?, ?, now() + make_interval(secs => ?))
          ON CONFLICT (kind, key) DO UPDATE
             SET holder = EXCLUDED.holder, expires_at = EXCLUDED.expires_at
           WHERE nessy_lease.expires_at < now()
      RETURNING holder
      """;

  // Only the holder that took it may release it: a slow holder whose lease was taken over must
  // not release the new holder's.
  private static final String RELEASE =
      "DELETE FROM nessy_lease WHERE kind = ? AND key = ? AND holder = ?";

  private final JdbcClient jdbc;
  private final String kind;
  private final Duration ttl;

  public JdbcLeases(JdbcClient jdbc, String kind, Duration ttl) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.kind = Objects.requireNonNull(kind, "kind must not be null");
    this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    if (kind.isBlank()) {
      throw new IllegalArgumentException("kind must not be blank");
    }
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
  }

  public JdbcLeases(DataSource dataSource, String kind, Duration ttl) {
    this(
        JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null")),
        kind,
        ttl);
  }

  @Override
  public <T> Attempt<T> tryWithLock(String key, Supplier<T> work) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(work, "work must not be null");
    UUID holder = UUID.randomUUID();
    boolean taken =
        jdbc.sql(TAKE)
            .params(kind, key, holder, ttl.toMillis() / 1000.0)
            .query(UUID.class)
            .optional()
            .filter(holder::equals)
            .isPresent();
    if (!taken) {
      return new Attempt.Ignored<>();
    }
    try {
      return new Attempt.Ran<>(work.get());
    } finally {
      jdbc.sql(RELEASE).params(kind, key, holder).update();
    }
  }
}
