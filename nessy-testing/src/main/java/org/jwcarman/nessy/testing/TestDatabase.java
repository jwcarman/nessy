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
package org.jwcarman.nessy.testing;

import javax.sql.DataSource;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * A database for a test: H2, in memory, with every module's schema already in it.
 *
 * <p><b>"In memory" is a {@link DataSource} choice, not a second implementation.</b> There is one
 * store per purpose and it speaks SQL, so a test exercises the same statements production runs
 * rather than a hand-written double that can quietly disagree with them.
 *
 * <p>It runs the real {@link Schemas#initialize(DataSource)} for the same reason: a test that
 * hand-writes its own DDL stops proving the schema, and would let a vendor-specific spelling reach
 * production unnoticed.
 *
 * <p><b>Per class, not per method.</b> Building a database and running the DDL is milliseconds —
 * nothing once, something across a few hundred tests. Take a fresh one per method only where a test
 * genuinely needs an empty slate.
 */
public final class TestDatabase {

  private TestDatabase() {}

  /**
   * A fresh, initialized database, isolated from every other test in this JVM.
   *
   * <p>{@code generateUniqueName} is what provides that isolation: H2's in-memory databases are
   * shared by NAME within a JVM, so two tests asking for the same one would see each other's rows.
   *
   * <p>Close it — {@link EmbeddedDatabase#shutdown()} — when the test is done with it.
   */
  public static EmbeddedDatabase fresh() {
    EmbeddedDatabase database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build();
    Schemas.initialize(database);
    return database;
  }
}
