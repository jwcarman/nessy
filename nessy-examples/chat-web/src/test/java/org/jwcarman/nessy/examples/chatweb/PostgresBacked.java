package org.jwcarman.nessy.examples.chatweb;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real PostgreSQL for the whole application: the engine's schema is PostgreSQL's.
 *
 * <p>Wired through properties rather than {@code @ServiceConnection}: Boot's connection-details
 * factories are written for the testcontainers 2.x container classes, and the build holds the 1.x
 * line (see the parent POM). One container for every test class, started once.
 */
abstract class PostgresBacked {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
