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
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.agent.BacklogItem;
import org.jwcarman.nessy.api.agent.Coalescer;

@DisplayName("The document an agent persists")
class AgentStateTest {

  @Test
  void an_idle_agent_holds_no_turn() {
    AgentState state = AgentState.idle();

    assertThat(state.phase()).isInstanceOf(Phase.Idle.class);
    assertThat(state.turnId()).isNull();
  }

  @Test
  void starting_a_turn_names_it() {
    AgentState state = AgentState.idle().startingTurn("turn-1");

    assertThat(state.turnId()).isEqualTo("turn-1");
  }

  @Test
  void the_serialised_form_round_trips_through_the_state_serializer() {
    AgentState before =
        AgentState.idle().startingTurn("turn-1").withPhase(new Phase.CallingModel());

    StateSerializer codec = new StateSerializer();
    Object after = codec.fromBinary(codec.toBinary(before), StateSerializer.AGENT_STATE_V2);

    assertThat(after).isEqualTo(before);
  }

  @Test
  void a_working_tools_phase_carrying_real_tool_calls_round_trips_through_the_state_serializer() {
    ToolCallRecord unsettled =
        ToolCallRecord.asked(
            "call-1",
            "prune_images",
            "{}",
            "docker image prune -af",
            Instant.parse("2026-08-24T12:00:00Z"));
    ToolCallRecord decidedAndSettled =
        ToolCallRecord.asked(
                "call-2", "disk_usage", "{}", "df -h", Instant.parse("2026-08-24T12:00:00Z"))
            .decidedBy(
                new ToolCallRecord.Decision(
                    true, "james", "go ahead", Instant.parse("2026-08-24T12:05:00Z")))
            .settle();
    AgentState before =
        AgentState.idle()
            .startingTurn("turn-1")
            .withPhase(new Phase.WorkingTools(List.of(unsettled, decidedAndSettled)));

    StateSerializer codec = new StateSerializer();
    Object after = codec.fromBinary(codec.toBinary(before), StateSerializer.AGENT_STATE_V2);

    assertThat(after).isEqualTo(before);
  }

  @Test
  void the_waiting_backlog_round_trips_through_the_state_serializer() {
    AgentState before =
        AgentState.idle()
            .ingesting(
                Coalescer.none(),
                new BacklogItem<>(
                    "entry-42", "the disk is full", Instant.parse("2026-08-28T12:00:00Z")))
            .taking()
            .startingTurn("turn-1")
            .withPhase(new Phase.CallingModel());

    StateSerializer codec = new StateSerializer();
    Object after = codec.fromBinary(codec.toBinary(before), StateSerializer.AGENT_STATE_V2);

    assertThat(after).isEqualTo(before);
    assertThat(((AgentState) after).current().observation()).isEqualTo("the disk is full");
  }

  @Test
  void a_coalescer_cannot_supersede_the_observation_a_turn_is_already_running_on() {
    // A policy that merges EVERYTHING onto one key -- the most aggressive case there is.
    Coalescer<String> supersedeEverything = Coalescer.byKey(text -> java.util.Optional.of("k"));
    AgentState working =
        AgentState.idle()
            .ingesting(supersedeEverything, new BacklogItem<>("a", "first", Instant.EPOCH))
            .taking()
            .startingTurn("turn-1")
            .withPhase(new Phase.CallingModel());

    AgentState afterArrival =
        working.ingesting(supersedeEverything, new BacklogItem<>("b", "second", Instant.EPOCH));

    // Left in the backlog, "a" would have been merged away and the turn would have finished by
    // discarding an item it never processed.
    assertThat(afterArrival.current().id()).isEqualTo("a");
    assertThat(afterArrival.current().observation()).isEqualTo("first");
    assertThat(afterArrival.backlog()).hasSize(1);
    assertThat(afterArrival.backlog().getFirst().observation()).isEqualTo("second");
  }

  @Test
  void a_finished_turn_lets_go_of_what_it_was_working_on() {
    AgentState working =
        AgentState.idle()
            .ingesting(Coalescer.none(), new BacklogItem<>("a", "first", Instant.EPOCH))
            .ingesting(Coalescer.none(), new BacklogItem<>("b", "second", Instant.EPOCH))
            .taking()
            .startingTurn("turn-1");

    assertThat(working.current().id()).isEqualTo("a");
    assertThat(working.backlog()).hasSize(1);
    assertThat(working.finishedTurn().inFlight()).isNull();
    assertThat(working.finishedTurn().hasWork()).isTrue();
  }
}
