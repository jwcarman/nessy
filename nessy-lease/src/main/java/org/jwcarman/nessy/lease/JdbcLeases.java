package org.jwcarman.nessy.lease;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Leases as rows in {@code nessy_lease}.
 *
 * <p><b>One statement to take.</b> An insert that, on finding the row already there, takes it over
 * only if it has expired -- and says which happened by returning the holder. A caller whose holder
 * comes back is the holder; one whose does not was refused. No read-then-write, so two callers
 * cannot both conclude the lease is free: the row lock inside the {@code INSERT ... ON CONFLICT}
 * serialises them.
 *
 * <p><b>The database's clock.</b> Expiry is compared against {@code now()} in the statement, so
 * holders on different machines need not agree on the time.
 */
public class JdbcLeases implements Leases {

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

  public JdbcLeases(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  public JdbcLeases(DataSource dataSource) {
    this(JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null")));
  }

  @Override
  public boolean tryRun(String kind, String key, Duration ttl, Runnable work) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(ttl, "ttl must not be null");
    Objects.requireNonNull(work, "work must not be null");
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    UUID holder = UUID.randomUUID();
    boolean taken =
        jdbc.sql(TAKE)
            .params(kind, key, holder, ttl.toMillis() / 1000.0)
            .query(UUID.class)
            .optional()
            .filter(holder::equals)
            .isPresent();
    if (!taken) {
      return false;
    }
    try {
      work.run();
    } finally {
      jdbc.sql(RELEASE).params(kind, key, holder).update();
    }
    return true;
  }
}
