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
package org.jwcarman.nessy.engine.story;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.backend.jdbc.JdbcAgentEvents;
import org.jwcarman.nessy.backend.jdbc.JdbcDirectBackend;
import org.jwcarman.nessy.backend.jdbc.Schemas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** A clock can say nanoseconds and PostgreSQL keeps microseconds; the story must not notice. */
@Tag("container")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EventAgentStoriesJdbcTest {

  private static final AgentType TYPE = new AgentType("desk");
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  @Test
  void a_clock_finer_than_the_database_keeps_still_hears_what_the_replay_reads() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
    Instant nanos = Instant.parse("2026-01-01T00:00:00.123456789Z");
    AgentId agent = AgentId.random();

    List<Narrated> heard =
        StoryTurn.heard(
            TYPE,
            agent,
            new JdbcDirectBackend(database, new JdbcTransactionManager(database), codecs),
            Clock.fixed(nanos, ZoneOffset.UTC));

    List<Narrated> replayed =
        new EventAgentStories(new JdbcAgentEvents(JdbcClient.create(database), codecs))
            .of(TYPE, agent)
            .replay(Seq.NONE, 100);
    assertThat(heard).isNotEmpty();
    assertThat(replayed).isEqualTo(heard);
  }
}
