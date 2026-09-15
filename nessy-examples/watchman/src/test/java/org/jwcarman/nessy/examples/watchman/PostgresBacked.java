package org.jwcarman.nessy.examples.watchman;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real PostgreSQL, once, for every test that touches a table: the engine's schema is PostgreSQL's
 * and so is the board's. Wired through properties rather than {@code @ServiceConnection}, because
 * Boot's connection-details factories are written for the testcontainers 2.x container classes and
 * the build holds the 1.x line.
 */
abstract class PostgresBacked {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  static DataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
