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
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.backend.backlog.Backlog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** The row that says an agent exists, and whether it has been terminated. */
@Tag("container")
@DisplayName("An agent in a database")
class JdbcAgentsTest {

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

  private final JdbcClient jdbc = JdbcClient.create(database());
  private final Codec<String> codec =
      new JacksonCodecFactory(JsonMapper.builder().build()).create(String.class);
  private final JdbcAgents agents = new JdbcAgents(jdbc);

  private static BacklogItem<String> said(String what) {
    return new BacklogItem<>(what, Instant.parse("2026-09-25T12:00:00Z"));
  }

  @Test
  @DisplayName("ensuring a new agent brings it into being")
  void ensuring_a_new_agent_is_not_terminated() {
    AgentId agent = AgentId.random();

    agents.ensure(TYPE, agent);

    assertThat(agents.terminated(TYPE, agent)).isFalse();
  }

  @Test
  @DisplayName("ensuring is idempotent, and safe to call every time")
  void ensuring_twice_does_not_fail() {
    AgentId agent = AgentId.random();

    agents.ensure(TYPE, agent);
    agents.ensure(TYPE, agent);

    assertThat(agents.terminated(TYPE, agent)).isFalse();
  }

  /**
   * Termination cannot reach an agent mid-turn, so it waits here. The mark and the emptying of the
   * backlog belong to the same statement pair, so an agent cannot be left terminated with work
   * still queued behind it.
   */
  @Test
  @DisplayName("sealing marks the agent and abandons what was waiting")
  void sealing_abandons_what_was_waiting() {
    AgentId terminating = AgentId.random();
    agents.ensure(TYPE, terminating);
    Backlog<String> backlog = new JdbcBacklog<>(jdbc, codec, agents, TYPE, terminating);
    backlog.append(said("never going to happen"));

    assertThat(agents.seal(TYPE, terminating))
        .as("work thrown away is counted, not vanished")
        .isEqualTo(1);

    assertThat(backlog.all()).as("whatever was waiting is abandoned").isEmpty();
    assertThat(agents.terminated(TYPE, terminating)).isTrue();
  }

  @Test
  @DisplayName("sealing an agent with nothing waiting abandons nothing")
  void sealing_an_idle_agent_abandons_nothing() {
    AgentId terminating = AgentId.random();
    agents.ensure(TYPE, terminating);

    assertThat(agents.seal(TYPE, terminating)).isZero();
    assertThat(agents.terminated(TYPE, terminating)).isTrue();
  }

  /**
   * The invariant a queued harness depends on. Nothing may be coalesced into a sealed agent --
   * sealing twice must still answer that the agent has been terminated.
   */
  @Test
  @DisplayName("sealing twice is still sealed, and still says terminated")
  void sealing_is_not_undone() {
    AgentId terminating = AgentId.random();
    agents.ensure(TYPE, terminating);
    Backlog<String> backlog = new JdbcBacklog<>(jdbc, codec, agents, TYPE, terminating);
    backlog.append(said("in flight when it was terminated"));

    agents.seal(TYPE, terminating);
    assertThat(agents.seal(TYPE, terminating))
        .as("nothing left to abandon the second time")
        .isZero();

    assertThat(agents.terminated(TYPE, terminating)).isTrue();
  }

  @Test
  @DisplayName("agents do not see each other's termination")
  void agents_are_separate() {
    AgentId terminated = AgentId.random();
    AgentId living = AgentId.random();
    agents.ensure(TYPE, terminated);
    agents.ensure(TYPE, living);

    agents.seal(TYPE, terminated);

    assertThat(agents.terminated(TYPE, terminated)).isTrue();
    assertThat(agents.terminated(TYPE, living)).isFalse();
  }
}
