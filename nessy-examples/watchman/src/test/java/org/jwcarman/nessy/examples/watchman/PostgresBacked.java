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
package org.jwcarman.nessy.examples.watchman;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL for every test that touches a table: the engine's schema is PostgreSQL's. A
 * Boot test imports {@link Connection} and gets it as a service connection.
 *
 * <p>One container per Spring context, started and stopped with it. The tests that use it share an
 * agent id, so a database shared between their contexts would let one test's rounds show up in
 * another's.
 */
final class PostgresBacked {

  private PostgresBacked() {}

  @TestConfiguration(proxyBeanMethods = false)
  static class Connection {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return new PostgreSQLContainer("postgres:18-alpine");
    }
  }
}
