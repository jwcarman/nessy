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
package org.jwcarman.nessy.engine.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ModelUsage;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.UsageReport;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.jdbc.JdbcAgentEvents;
import org.jwcarman.nessy.backend.jdbc.Schemas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** On PostgreSQL the two doors' stores are views of the same tables: an agent counts once. */
@Tag("container")
@DisplayName("Usage reports over a database")
class EventUsageReportsJdbcTest {

  private static final AgentType TYPE = new AgentType("desk");
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  private static AgentEvents store(DataSource database) {
    return new JdbcAgentEvents(
        JdbcClient.create(database), new JacksonCodecFactory(JsonMapper.builder().build()));
  }

  @Test
  @DisplayName("a story stored once is counted once, though two stores can read it")
  void two_views_of_one_table_count_an_agent_once() {
    DataSource database = database();
    AgentEvents direct = store(database);
    AgentEvents queued = store(database);
    AgentId agent = AgentId.random();
    Usage spent =
        new Usage(
            "qwen", Tokens.of(1000), Tokens.of(50), Tokens.none(), Tokens.none(), Tokens.of(0));
    direct.append(
        TYPE,
        agent,
        List.of(
            new AgentEvent.TurnStarted(
                new Seq(1),
                new TurnId(1),
                new PayloadRef("p"),
                "Question",
                Instant.EPOCH,
                Instant.EPOCH),
            new AgentEvent.InferenceAnswered(
                new Seq(2), new TurnId(1), new PayloadRef("a"), false, spent, Optional.empty())),
        Seq.NONE,
        Instant.EPOCH);

    UsageReport report = new EventUsageReports(List.of(queued, direct)).of(TYPE, agent);

    assertThat(report.byModel())
        .containsExactly(
            new ModelUsage(
                "qwen",
                1,
                Tokens.of(1000),
                Tokens.of(50),
                Tokens.none(),
                Tokens.none(),
                Tokens.of(0)));
  }
}
