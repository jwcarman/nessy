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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.backlog.Backlog;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Tag("container")
@DisplayName("Counting the inputs a database agent has been told")
class JdbcQueuedCountTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static final AgentType TYPE = new AgentType("counted");
  private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

  private final DataSource dataSource = database();
  private final QueuedBackend backend =
      new JdbcQueuedBackend(
          dataSource,
          new JdbcTransactionManager(dataSource),
          new JacksonCodecFactory(JsonMapper.builder().build()));
  private final AgentId agent = AgentId.random();

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  @Test
  void the_count_of_inputs_told_and_not_started() {
    backend.agents().ensure(TYPE, agent);
    Backlog<String> backlog = backend.backlogs(TypeRef.of(String.class)).forAgent(TYPE, agent);
    backlog.append(new BacklogItem<>("one", NOW));
    backlog.append(new BacklogItem<>("two", NOW));
    backlog.append(new BacklogItem<>("three", NOW));
    backlog.take();

    assertThat(backend.queued(TYPE, agent)).isEqualTo(2);
  }

  @Test
  void an_agent_nobody_told_anything_has_nothing_queued() {
    assertThat(backend.queued(TYPE, agent)).isZero();
  }
}
