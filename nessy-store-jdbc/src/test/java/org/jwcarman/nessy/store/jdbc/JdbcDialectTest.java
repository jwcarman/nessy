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
package org.jwcarman.nessy.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.InboxEntry;
import org.jwcarman.nessy.api.message.TextBlock;

/**
 * {@link JdbcDialect#resolve(DatabaseMetaData)}, pinned without a database: a hand-rolled
 * dynamic-proxy {@link DatabaseMetaData} double (the house bans mocking libraries, not test doubles
 * — the same pattern {@code JdbcFailureWrappingTest} already uses for {@link java.sql.Connection})
 * stands in for every product-name/version combination design §2 enumerates.
 */
class JdbcDialectTest {

  private static DatabaseMetaData metaData(String productName, String productVersion) {
    return (DatabaseMetaData)
        Proxy.newProxyInstance(
            DatabaseMetaData.class.getClassLoader(),
            new Class<?>[] {DatabaseMetaData.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getDatabaseProductName" -> productName;
                  case "getDatabaseProductVersion" -> productVersion;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  @Nested
  class The_product_name_table {

    @Test
    void postgre_sql_resolves_to_postgres() throws Exception {
      assertThat(JdbcDialect.resolve(metaData("PostgreSQL", "17.11")))
          .isEqualTo(JdbcDialect.POSTGRES);
    }

    @Test
    void cockroach_db_reporting_postgre_sql_also_resolves_to_postgres() throws Exception {
      // CockroachDB and Yugabyte both report "PostgreSQL" deliberately, for exactly this kind of
      // wire-compatible detection — see JdbcDialect's class javadoc.
      assertThat(JdbcDialect.resolve(metaData("PostgreSQL", "CockroachDB CCL v23.2.0")))
          .isEqualTo(JdbcDialect.POSTGRES);
    }

    @Test
    void my_sql_resolves_to_mysql() throws Exception {
      assertThat(JdbcDialect.resolve(metaData("MySQL", "8.0.36"))).isEqualTo(JdbcDialect.MYSQL);
    }

    @Test
    void maria_db_reporting_its_own_product_name_resolves_to_mariadb() throws Exception {
      assertThat(JdbcDialect.resolve(metaData("MariaDB", "11.4.12-MariaDB")))
          .isEqualTo(JdbcDialect.MARIADB);
    }

    @Test
    void microsoft_sql_server_resolves_to_sqlserver() throws Exception {
      assertThat(JdbcDialect.resolve(metaData("Microsoft SQL Server", "16.00.4165")))
          .isEqualTo(JdbcDialect.SQLSERVER);
    }

    @Test
    void oracle_resolves_to_oracle() throws Exception {
      assertThat(JdbcDialect.resolve(metaData("Oracle", "23.26.2.0.0")))
          .isEqualTo(JdbcDialect.ORACLE);
    }
  }

  @Nested
  class The_maria_db_sniff {

    @Test
    void my_sql_product_name_with_a_mariadb_flavored_version_string_resolves_to_mariadb()
        throws Exception {
      // The MariaDB Connector/J driver reports the MySQL product name for compatibility but
      // stamps its own name into the version string — the Hibernate sniff design §2 borrows.
      assertThat(JdbcDialect.resolve(metaData("MySQL", "11.4.12-MariaDB-ubu2404")))
          .isEqualTo(JdbcDialect.MARIADB);
    }

    @Test
    void my_sql_product_name_with_a_mariadb_flavored_version_string_is_case_insensitive()
        throws Exception {
      assertThat(JdbcDialect.resolve(metaData("MySQL", "11.4.12-mariadb-ubu2404")))
          .isEqualTo(JdbcDialect.MARIADB);
    }

    @Test
    void my_sql_product_name_with_an_ordinary_version_string_stays_mysql() throws Exception {
      assertThat(JdbcDialect.resolve(metaData("MySQL", "8.0.36"))).isEqualTo(JdbcDialect.MYSQL);
    }
  }

  @Nested
  class An_unrecognized_product {

    @Test
    void fails_loudly_naming_the_product_and_every_supported_dialect() {
      DatabaseMetaData unknown = metaData("H2", "2.2.224");

      assertThatThrownBy(() -> JdbcDialect.resolve(unknown))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("H2")
          .hasMessageContaining("POSTGRES")
          .hasMessageContaining("MYSQL")
          .hasMessageContaining("MARIADB")
          .hasMessageContaining("SQLSERVER")
          .hasMessageContaining("ORACLE");
    }

    @Test
    void names_the_explicit_dialect_override_as_the_escape_hatch() {
      DatabaseMetaData unknown = metaData("H2", "2.2.224");

      assertThatThrownBy(() -> JdbcDialect.resolve(unknown))
          .hasMessageContaining("nessy.jdbc.dialect");
    }
  }

  @Nested
  class An_explicit_override {

    /**
     * A store built with an explicit dialect must never ask a connection what it is: {@link
     * #getMetaData} throws if called, so any accidental resolution attempt fails the test loudly
     * rather than silently agreeing with the (deliberately wrong) override.
     */
    @Test
    void a_store_built_with_an_explicit_dialect_never_asks_a_connection_to_resolve_it() {
      Connection refusesMetadata =
          (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, args) ->
                      switch (method.getName()) {
                        case "getMetaData" ->
                            throw new AssertionError(
                                "override should have made resolution unnecessary");
                        case "prepareStatement" -> throw REFUSED_AFTER_DIALECT_RESOLVED;
                        case "close" -> null;
                        case "isClosed" -> false;
                        default -> throw new UnsupportedOperationException(method.getName());
                      });
      JdbcConversationStore store =
          new JdbcConversationStore(
              new OneConnectionDataSource(refusesMetadata), new ObjectMapper(), JdbcDialect.ORACLE);

      // append() reaches statementsFor(connection) before prepareStatement; had the override not
      // won, statementsFor would call getMetaData() and this test would fail with the
      // AssertionError above instead of the expected wrapped SQLException.
      assertThatThrownBy(() -> store.append(ConversationId.generate(), aTold()))
          .isInstanceOf(IllegalStateException.class)
          .cause()
          .isSameAs(REFUSED_AFTER_DIALECT_RESOLVED);
    }

    private static final SQLException REFUSED_AFTER_DIALECT_RESOLVED =
        new SQLException("refused (test) — reached after the dialect override was honored");

    private InboxEntry aTold() {
      return InboxEntry.told(List.of(new TextBlock("hi")));
    }
  }

  /** Hand-rolled: lends exactly the one connection it was given. */
  private static final class OneConnectionDataSource implements DataSource {

    private final Connection connection;

    private OneConnectionDataSource(Connection connection) {
      this.connection = connection;
    }

    @Override
    public Connection getConnection() {
      return connection;
    }

    @Override
    public Connection getConnection(String username, String password) {
      return connection;
    }

    @Override
    public PrintWriter getLogWriter() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
