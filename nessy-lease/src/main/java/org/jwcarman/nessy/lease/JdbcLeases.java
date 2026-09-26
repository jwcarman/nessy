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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.lock.LockKind;
import org.jwcarman.nessy.backend.lock.Locks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>The time-to-live is settled here, at construction, one per kind.</b> James: "forcing folks
 * to think about their TTL is smarter than having a global default." A kind absent from the map is
 * refused at the call rather than silently defaulted -- whoever wires this up is the one who knows
 * how long that kind of work takes. Err long: a lease that expires while its holder is still
 * working lets two run at once, which is the failure this exists to prevent, whereas one that
 * outlives a crash only delays the next attempt.
 */
public class JdbcLeases implements Locks {

  private static final Logger LOG = LoggerFactory.getLogger(JdbcLeases.class);

  private static final String TAKE =
      """
      INSERT INTO nessy_lease (kind, agent_type, agent_id, holder, expires_at)
      VALUES (?, ?, ?, ?, now() + make_interval(secs => ?))
          ON CONFLICT (kind, agent_type, agent_id) DO UPDATE
             SET holder = EXCLUDED.holder,
                 expires_at = EXCLUDED.expires_at,
                 takeovers = nessy_lease.takeovers + 1
           WHERE nessy_lease.expires_at < now()
      RETURNING holder, takeovers
      """;

  // Only the holder that took it may release it: a slow holder whose lease was taken over must
  // not release the new holder's.
  private static final String RELEASE =
      "DELETE FROM nessy_lease WHERE kind = ? AND agent_type = ? AND agent_id = ? AND holder = ?";

  private record Taken(UUID holder, int takeovers) {}

  private final JdbcClient jdbc;
  private final Map<LockKind, Duration> ttls;

  public JdbcLeases(JdbcClient jdbc, Map<LockKind, Duration> ttls) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    Objects.requireNonNull(ttls, "ttls must not be null");
    ttls.forEach(
        (kind, ttl) -> {
          Objects.requireNonNull(ttl, "the ttl for " + kind + " must not be null");
          if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("the ttl for " + kind + " must be positive");
          }
        });
    this.ttls = Map.copyOf(ttls);
  }

  public JdbcLeases(DataSource dataSource, Map<LockKind, Duration> ttls) {
    this(
        JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null")), ttls);
  }

  private Duration ttl(LockKind kind) {
    Duration ttl = ttls.get(kind);
    if (ttl == null) {
      throw new IllegalArgumentException("no ttl configured for lock kind " + kind.value());
    }
    return ttl;
  }

  @Override
  public <T> Attempt<T> tryWithLock(
      LockKind kind, AgentType type, AgentId agent, Supplier<T> work) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(work, "work must not be null");
    Duration ttl = ttl(kind);
    UUID holder = UUID.randomUUID();
    Taken taken =
        jdbc.sql(TAKE)
            .params(kind.value(), type.value(), agent.value(), holder, ttl.toMillis() / 1000.0)
            .query((rs, _) -> new Taken((UUID) rs.getObject("holder"), rs.getInt("takeovers")))
            .optional()
            .orElse(null);
    if (taken == null) {
      return new Attempt.Ignored<>();
    }
    if (taken.takeovers() > 0) {
      // Nothing here can tell a dead holder from a slow one; this states the fact and does not
      // guess which it was.
      LOG.info(
          "lease {}/{}/{} was taken over from a holder that had not released it (takeover #{})",
          kind.value(),
          type.value(),
          agent.value(),
          taken.takeovers());
    }
    try {
      return new Attempt.Ran<>(work.get());
    } finally {
      int released =
          jdbc.sql(RELEASE).params(kind.value(), type.value(), agent.value(), holder).update();
      if (released == 0) {
        // The only observer that can prove duplicate work happened: this holder's row is gone,
        // so somebody else took the lease over while this call was still working.
        LOG.warn(
            "lease {}/{}/{} was taken over while this holder was still working; its work may have"
                + " run twice",
            kind.value(),
            type.value(),
            agent.value());
      }
    }
  }
}
