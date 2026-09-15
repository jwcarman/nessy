package org.jwcarman.nessy.examples.watchman;

import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL, once, for every test that touches a table: the engine's schema is PostgreSQL's
 * and so is the board's. A Boot test imports {@link Connection} and gets it as a service
 * connection; a test with no context asks {@link #dataSource()}.
 */
final class PostgresBacked {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private PostgresBacked() {}

  static DataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Connection {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return POSTGRES;
    }
  }
}
