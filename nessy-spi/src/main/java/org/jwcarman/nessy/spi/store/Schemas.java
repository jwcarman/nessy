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
package org.jwcarman.nessy.spi.store;

import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Creates the tables Nessy's modules need, from the DDL each of them ships.
 *
 * <p>Every module that persists something puts a {@code nessy-schema.sql} at the root of its own
 * jar. This gathers them with {@code classpath*:}, which enumerates EVERY matching resource rather
 * than the first — the same mechanism Spring Boot's own script initialization uses — so adding a
 * module to the classpath brings its tables with it and nothing has to be registered.
 *
 * <p><b>The name is the opt-in.</b> Boot's defaults look for {@code schema.sql}, so nothing here is
 * ever run against an application's database uninvited; and because our files are named {@code
 * nessy-schema.sql}, this never runs the APPLICATION's DDL either. Whether it runs at all is the
 * caller's decision — see the engine, which initializes a {@link DataSource} it created and one it
 * was handed only on request.
 *
 * <p><b>This is a bootstrap, not a migration.</b> {@code CREATE TABLE IF NOT EXISTS} handles "the
 * table is absent" and nothing else: change a column and this silently does nothing, and the
 * mismatch surfaces at query time rather than at startup. That is an accepted trade before 1.0, and
 * the point at which it stops being acceptable is the point this wants versioned scripts.
 *
 * <p><b>Write ANSI SQL in those files, never vendor aliases.</b> Measured against H2 2.3.232:
 * {@code TEXT}, {@code BYTEA}, {@code BIGINT}, composite primary keys, {@code IF NOT EXISTS} and
 * {@code LIMIT ?} all work on both it and PostgreSQL. The one failure was {@code TIMESTAMPTZ},
 * which is a PostgreSQL alias — {@code TIMESTAMP WITH TIME ZONE} is the standard spelling and both
 * accept it. And no reserved words as identifiers: {@code key} is reserved in H2 and merely
 * unreserved in PostgreSQL, so a column named {@code key} passes there and fails here. One DDL file
 * per module serves every database only while both rules hold — which is why {@code SchemasTest}
 * runs real DDL against H2 rather than trusting anyone to remember them.
 */
public final class Schemas {

  /** Where a module declares the tables it owns. */
  public static final String LOCATION = "classpath*:nessy-schema.sql";

  private Schemas() {}

  /**
   * Runs every module's DDL against {@code dataSource}.
   *
   * <p>Idempotent, because every statement in those files is {@code IF NOT EXISTS} — running it on
   * an initialized database is a no-op rather than an error, which is what makes it safe to call on
   * every start.
   *
   * @throws IllegalArgumentException if {@code dataSource} is null
   */
  public static void initialize(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(declared()), dataSource);
  }

  /**
   * The DDL files found on the classpath, in no particular order.
   *
   * <p>Order is deliberately not promised: no module's tables reference another's, so nothing
   * depends on it. A foreign key across modules would break that, which is a reason not to add one.
   *
   * <p>Exposed so an application can log what it is about to create — or what it would have created
   * — without touching a database. On a first deploy that is the difference between a schema
   * question and a guess.
   */
  public static Resource[] declared() {
    try {
      return new PathMatchingResourcePatternResolver().getResources(LOCATION);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("could not scan the classpath for " + LOCATION, e);
    }
  }
}
