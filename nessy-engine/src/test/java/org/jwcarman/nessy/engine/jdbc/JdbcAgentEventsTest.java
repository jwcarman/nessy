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

package org.jwcarman.nessy.engine.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEventConflict;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.jdbc.JdbcAgentEvents;
import org.jwcarman.nessy.backend.jdbc.Schemas;
import org.jwcarman.nessy.engine.core.AgentState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Reading an agent back costs the last turn, not the whole life. */
@Tag("container")
@DisplayName("An agent's events in a database")
class JdbcAgentEventsTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

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

  private final AgentEvents events =
      new JdbcAgentEvents(
          JdbcClient.create(database()), new JacksonCodecFactory(JsonMapper.builder().build()));

  private final AgentId agent = AgentId.random();
  private final PayloadRef somewhere = new PayloadRef("p1");

  private static AgentEvent started(long seq, long turn) {
    return new AgentEvent.TurnStarted(
        new Seq(seq), new TurnId(turn), new PayloadRef("p1"), Instant.EPOCH);
  }

  private AgentEvent answered(long seq, long turn) {
    return new AgentEvent.InferenceAnswered(
        new Seq(seq), new TurnId(turn), somewhere, Usage.unreported());
  }

  /** Rebuilt the way a harness rebuilds it: the last turn, replayed onto idle. */
  private AgentState reconstituted() {
    List<AgentEvent> lastTurn = events.sinceLastTurnStarted(TYPE, agent);
    Seq from =
        lastTurn.isEmpty() ? Seq.NONE : new Seq(Math.max(0, lastTurn.getFirst().seq().value() - 1));
    return AgentState.idle(from).applyAll(lastTurn);
  }

  @Test
  @DisplayName("an agent nobody has written to has no last turn, and comes back idle")
  void a_new_agent_is_idle() {
    assertThat(events.sinceLastTurnStarted(TYPE, agent)).isEmpty();
    assertThat(reconstituted()).isInstanceOf(AgentState.Idle.class);
  }

  @Test
  @DisplayName("past a closed turn there is nothing to read, and the agent comes back idle")
  void a_closed_turn_leaves_nothing_to_replay() {
    events.append(TYPE, agent, List.of(started(1, 1), answered(2, 1)), Seq.NONE, AT);

    assertThat(events.sinceLastTurnStarted(TYPE, agent))
        .as("the last turn, and nothing before it")
        .hasSize(2);
    assertThat(reconstituted())
        .as("a turn that ended leaves the agent idle, wherever it ended")
        .isEqualTo(AgentState.idle(new Seq(2)));
  }

  @Test
  @DisplayName("in the middle of a turn, what comes back is that turn")
  void an_open_turn_is_replayed() {
    events.append(TYPE, agent, List.of(started(1, 1), answered(2, 1)), Seq.NONE, AT);
    events.append(TYPE, agent, List.of(started(3, 3)), new Seq(2), AT);

    assertThat(events.sinceLastTurnStarted(TYPE, agent))
        .as("one turn's events, not a history")
        .hasSize(1);
    assertThat(reconstituted()).isInstanceOf(AgentState.Inferring.class);
  }

  @Test
  @DisplayName("a terminated agent stays terminated, however often it is read back")
  void termination_survives_replay() {
    events.append(TYPE, agent, List.of(started(1, 1), answered(2, 1)), Seq.NONE, AT);
    // Terminated starts no turn, so it falls inside the last one that did -- which is why it
    // needs no turn of its own, and why it is replayed every time the agent is read back.
    events.append(TYPE, agent, List.of(new AgentEvent.Terminated(new Seq(3))), new Seq(2), AT);

    assertThat(reconstituted()).isInstanceOf(AgentState.Terminal.class);
    assertThat(reconstituted()).isInstanceOf(AgentState.Terminal.class);
  }

  @Test
  @DisplayName("a writer that reached a seq first takes the second one down")
  void a_seq_already_taken_is_a_conflict() {
    events.append(TYPE, agent, List.of(started(1, 1)), Seq.NONE, AT);
    List<AgentEvent> sameSeq = List.of(answered(1, 1));

    assertThatThrownBy(() -> events.append(TYPE, agent, sameSeq, Seq.NONE, AT))
        .isInstanceOf(AgentEventConflict.class)
        .hasMessageContaining("another writer reached");
  }

  @Test
  @DisplayName("agents do not read each other's events")
  void agents_are_separate() {
    AgentId other = AgentId.random();
    events.append(TYPE, agent, List.of(started(1, 1)), Seq.NONE, AT);

    assertThat(events.readAll(TYPE, other)).isEmpty();
    assertThat(events.readAll(TYPE, agent)).hasSize(1);
  }

  @Test
  @DisplayName(
      "an appended event's writtenAt is the instant it was appended with, not the database's")
  void writtenAt_is_the_instant_given_at_append() {
    Instant given = Instant.parse("2026-01-01T00:00:00Z");
    events.append(TYPE, agent, List.of(started(1, 1)), Seq.NONE, given);

    assertThat(events.writtenAt(TYPE, agent, new Seq(1))).isEqualTo(given);
  }

  @Test
  @DisplayName("an unknown seq names the agent and the seq rather than staying quiet about it")
  void writtenAt_of_an_unknown_seq_throws() {
    events.append(TYPE, agent, List.of(started(1, 1)), Seq.NONE, AT);
    Seq unknownSeq = new Seq(99);

    assertThatThrownBy(() -> events.writtenAt(TYPE, agent, unknownSeq))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("99")
        .hasMessageContaining(agent.value().toString());
  }

  @Test
  @DisplayName("each event after the watermark comes back with the instant its batch was written")
  void streamWrittenFrom_pairs_each_event_with_its_instant() {
    Instant later = Instant.parse("2026-01-01T00:05:00Z");
    events.append(TYPE, agent, List.of(started(1, 1), answered(2, 1)), Seq.NONE, AT);
    events.append(TYPE, agent, List.of(started(3, 3)), new Seq(2), later);

    List<AgentEvents.Written> written;
    try (Stream<AgentEvents.Written> stream = events.streamWrittenFrom(TYPE, agent, new Seq(1))) {
      written = stream.toList();
    }

    assertThat(written)
        .containsExactly(
            new AgentEvents.Written(answered(2, 1), AT),
            new AgentEvents.Written(started(3, 3), later));
  }
}
