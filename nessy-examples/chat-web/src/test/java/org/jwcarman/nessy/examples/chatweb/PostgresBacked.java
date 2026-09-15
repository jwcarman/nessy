package org.jwcarman.nessy.examples.chatweb;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL for the whole application: the engine's schema is PostgreSQL's. Handed to Boot
 * as a service connection, so the DataSource points at it with no properties to spell out.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresBacked {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:18-alpine");
  }
}
