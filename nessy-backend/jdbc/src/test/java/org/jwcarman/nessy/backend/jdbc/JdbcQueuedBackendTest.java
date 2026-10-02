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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.backlog.Backlog;
import org.jwcarman.nessy.backend.backlog.Backlogs;
import org.jwcarman.nessy.backend.backlog.Pull;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.Attempt;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link JdbcQueuedBackend} hands back is not a copy of anything -- it is the same tables
 * every instance built over this database sees, which is what "shared" means, and every store it
 * builds actually does what its interface promises rather than merely being non-null.
 */
@Tag("container")
@DisplayName("A queued backend over a database")
class JdbcQueuedBackendTest {

  /**
   * What the Spring auto-configuration hands in, spelled out here. JdbcQueuedBackend takes a codec
   * factory rather than making one, so a test says which bytes it means exactly as an application
   * does.
   */
  private static QueuedBackend backend(DataSource dataSource) {
    return new JdbcQueuedBackend(
        dataSource,
        new JdbcTransactionManager(dataSource),
        new JacksonCodecFactory(JsonMapper.builder().build()));
  }

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static final AgentType TYPE = new AgentType("chat");

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  private final DataSource dataSource = database();

  @Test
  @DisplayName("hands back the database's chapters and leases")
  void chapters_and_leases_are_present() {
    QueuedBackend backend = backend(dataSource);

    assertThat(backend.chapters()).isInstanceOf(JdbcChapters.class);
    assertThat(backend.leases()).isInstanceOf(JdbcLeases.class);
  }

  @Test
  @DisplayName("an agent ensured through one instance is seen as ensured through another")
  void agents_are_shared_across_instances() {
    QueuedBackend writer = backend(dataSource);
    QueuedBackend reader = backend(dataSource);
    AgentId agent = AgentId.random();

    writer.agents().ensure(TYPE, agent);

    assertThat(reader.agents().terminated(TYPE, agent)).isFalse();
    assertThat(reader.agents().seal(TYPE, agent)).isZero();
    assertThat(writer.agents().terminated(TYPE, agent))
        .as("sealed through the reader, visible through the writer")
        .isTrue();
  }

  @Test
  @DisplayName("an effect inserted through one instance is claimable through another")
  void effects_are_shared_across_instances() {
    QueuedBackend writer = backend(dataSource);
    QueuedBackend reader = backend(dataSource);
    AgentId agent = AgentId.random();
    Instant now = Instant.parse("2026-09-26T12:00:00Z");
    writer.agents().ensure(TYPE, agent);

    writer
        .effects()
        .insert(
            TYPE,
            agent,
            new AgentEffect.Infer(new TurnId(1)),
            Duration.ofMinutes(1),
            new EffectOutcome.InferenceRefused("undispatchable", Usage.unreported()),
            now.plus(Duration.ofHours(1)),
            null,
            now);

    List<Attempt> claimed = reader.effects().markRunning(TYPE, now, 10);

    assertThat(claimed).hasSize(1);
    assertThat(claimed.getFirst().agentId()).isEqualTo(agent);
    assertThat(reader.effects().effectOf(claimed.getFirst()))
        .isEqualTo(new AgentEffect.Infer(new TurnId(1)));
  }

  @Test
  @DisplayName("a backlog built through one instance is readable and takeable through another")
  void backlogs_are_shared_across_instances() {
    QueuedBackend writer = backend(dataSource);
    QueuedBackend reader = backend(dataSource);
    AgentId agent = AgentId.random();
    writer.agents().ensure(TYPE, agent);
    Backlogs<String> writerBacklogs = writer.backlogs(TypeRef.of(String.class));
    Backlogs<String> readerBacklogs = reader.backlogs(TypeRef.of(String.class));

    Instant arrivedAt = Instant.parse("2026-09-26T12:00:00Z");
    writerBacklogs.forAgent(TYPE, agent).append(new BacklogItem<>("said something", arrivedAt));

    Backlog<String> read = readerBacklogs.forAgent(TYPE, agent);
    assertThat(read.size()).isOne();
    assertThat(read.take())
        .isEqualTo(new Pull.Item<>(new BacklogItem<>("said something", arrivedAt)));
    assertThat(read.take()).as("taken, and nothing left waiting").isEqualTo(new Pull.Empty<>());
  }
}
