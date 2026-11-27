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

import java.util.List;
import javax.sql.DataSource;
import liquibase.exception.LiquibaseException;
import org.jwcarman.migratory.autoconfigure.MigratoryLiquibase;
import org.jwcarman.migratory.autoconfigure.MigratoryProperties;
import org.jwcarman.migratory.core.Migration;
import org.jwcarman.migratory.core.MigrationDeclarations;
import org.jwcarman.migratory.core.MigrationSorter;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Creates the tables a container test needs, the way an application gets them: migratory's own
 * runner, over every migration declared on the test class path, in migratory's order.
 *
 * <p>Nothing here knows a table. A module's test class path carries its own changelog and those of
 * the modules it depends on, so each test gets exactly the tables an application with the same
 * modules would. Safe to call once per test class against a shared database: Liquibase records what
 * it ran and runs nothing twice.
 */
public final class Migrations {

  private Migrations() {}

  /** Runs every declared migration against {@code dataSource}. */
  public static void run(DataSource dataSource) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    List<Migration> migrations = MigrationSorter.sort(MigrationDeclarations.discover(loader));
    MigratoryLiquibase runner =
        new MigratoryLiquibase(
            migrations,
            dataSource,
            new LiquibaseProperties(),
            new MigratoryProperties(),
            new DefaultResourceLoader(loader));
    try {
      runner.afterPropertiesSet();
    } catch (LiquibaseException e) {
      throw new IllegalStateException("the declared migrations did not run", e);
    }
  }
}
