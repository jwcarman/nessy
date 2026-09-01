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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * The DDL each module ships, and the one call that runs all of it.
 *
 * <p>This test doubles as the enforcement of a rule prose cannot enforce: the sample schema beside
 * it is written in ANSI spellings, so a vendor alias like {@code TIMESTAMPTZ} fails here rather
 * than on someone's H2 deployment.
 */
@DisplayName("The schema every module declares")
class SchemasTest {

  private EmbeddedDatabase database;

  @BeforeEach
  void fresh() {
    database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build();
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  @Test
  @DisplayName("a module's file is found without being registered anywhere")
  void declared_finds_the_files_on_the_classpath() {
    assertThat(Schemas.declared()).isNotEmpty();
  }

  @Test
  void the_tables_a_module_declares_exist_afterwards() throws SQLException {
    assertThat(tableExists("NESSY_SCHEMA_PROBE")).isFalse();

    Schemas.initialize(database);

    assertThat(tableExists("NESSY_SCHEMA_PROBE")).isTrue();
  }

  /**
   * Every statement is {@code IF NOT EXISTS}, which is what makes it safe to run on every start
   * rather than only on the first.
   */
  @Test
  @DisplayName("running it on an initialized database is a no-op, not an error")
  void initializing_twice_is_harmless() throws SQLException {
    Schemas.initialize(database);
    Schemas.initialize(database);

    assertThat(tableExists("NESSY_SCHEMA_PROBE")).isTrue();
  }

  @Test
  void a_null_data_source_is_refused() {
    assertThatThrownBy(() -> Schemas.initialize(null)).isInstanceOf(NullPointerException.class);
  }

  private boolean tableExists(String name) throws SQLException {
    try (Connection connection = database.getConnection();
        ResultSet tables = connection.getMetaData().getTables(null, null, name, null)) {
      return tables.next();
    }
  }
}
