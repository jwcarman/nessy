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
package org.jwcarman.nessy.backend.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InMemoryAgentTurnsTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final Trajectory TRAJECTORY = new Trajectory((short) 1, "0".repeat(64));

  private static AgentTurn turn(long id) {
    Instant t = Instant.EPOCH.plusSeconds(id);
    return new AgentTurn(
        new TurnId(id),
        new Seq(id + 5),
        t,
        t,
        t,
        TRAJECTORY,
        "{\"rounds\":[],\"outcome\":\"ANSWERED\"}",
        "Q",
        true,
        TurnOutcome.ANSWERED,
        1,
        2,
        2,
        0,
        0,
        2,
        0);
  }

  private static final Instant AT = Instant.parse("2026-10-08T12:00:00Z");

  private static AgentTurn withNovel(AgentTurn row, boolean novel) {
    return new AgentTurn(
        row.turn(),
        row.endingSeq(),
        row.arrivedAt(),
        row.startedAt(),
        row.endedAt(),
        row.trajectory(),
        row.trajectoryJson(),
        row.label(),
        novel,
        row.outcome(),
        row.rounds(),
        row.toolCalls(),
        row.toolSuccesses(),
        row.toolFailures(),
        row.toolDenials(),
        row.inferenceCalls(),
        row.inferenceRetries());
  }

  private final AgentTurns turns = new InMemoryAgentTurns();

  @Test
  void the_first_sighting_of_a_trajectory_is_novel_and_the_second_is_not() {
    assertThat(turns.firstSighting(TYPE, "Q", TRAJECTORY, AT)).isTrue();
    assertThat(turns.firstSighting(TYPE, "Q", TRAJECTORY, AT.plusSeconds(1))).isFalse();
  }

  @Test
  void the_same_trajectory_under_another_label_type_or_version_is_novel_again() {
    turns.firstSighting(TYPE, "Q", TRAJECTORY, AT);
    assertThat(turns.firstSighting(TYPE, "R", TRAJECTORY, AT)).isTrue();
    assertThat(turns.firstSighting(new AgentType("other"), "Q", TRAJECTORY, AT)).isTrue();
    assertThat(turns.firstSighting(TYPE, "Q", new Trajectory((short) 2, TRAJECTORY.hash()), AT))
        .isTrue();
  }

  @Test
  void a_row_keeps_the_novelty_it_was_appended_with() {
    AgentTurn known = withNovel(turn(10), false);
    turns.append(TYPE, AGENT, known);
    assertThat(turns.of(TYPE, AGENT)).singleElement().extracting(AgentTurn::novel).isEqualTo(false);
  }

  @Test
  void a_recorded_turn_is_read_back_for_its_agent_oldest_first() {
    turns.append(TYPE, AGENT, turn(10));
    turns.append(TYPE, AGENT, turn(20));
    assertThat(turns.of(TYPE, AGENT)).containsExactly(turn(10), turn(20));
  }

  @Test
  void an_agent_with_no_turns_reads_back_empty() {
    assertThat(turns.of(TYPE, AGENT)).isEmpty();
  }

  @Test
  void another_agents_turns_are_not_this_ones() {
    turns.append(TYPE, AGENT, turn(10));
    assertThat(turns.of(TYPE, new AgentId(UUID.randomUUID()))).isEmpty();
  }

  @Test
  void a_turn_recorded_twice_is_refused() {
    AgentTurn first = turn(10);
    turns.append(TYPE, AGENT, first);
    AgentTurn again = turn(10);
    assertThatThrownBy(() -> turns.append(TYPE, AGENT, again))
        .isInstanceOf(IllegalStateException.class);
  }
}
