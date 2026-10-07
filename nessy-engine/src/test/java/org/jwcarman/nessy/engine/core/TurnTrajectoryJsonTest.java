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
package org.jwcarman.nessy.engine.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.engine.core.TurnTrajectory.CallOutcome;
import org.jwcarman.nessy.engine.core.TurnTrajectory.State;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TurnTrajectoryJsonTest {

  private static final ToolName A = new ToolName("a");
  private static final ToolName B = new ToolName("b");
  private static final ToolName C = new ToolName("c");

  private static State opened() {
    return State.opened(Instant.EPOCH);
  }

  @Test
  void a_turn_that_called_no_tool_has_no_rounds() {
    assertThat(TurnTrajectory.json(opened(), TurnOutcome.ANSWERED))
        .isEqualTo("{\"rounds\":[],\"outcome\":\"ANSWERED\"}");
  }

  @Test
  void a_round_is_written_in_the_order_the_hash_encodes_it_with_duplicates_kept() {
    State state =
        opened()
            .settled(B, CallOutcome.SUCCESS)
            .settled(A, CallOutcome.FAILED)
            .settled(A, CallOutcome.SUCCESS)
            .roundClosed();
    assertThat(TurnTrajectory.json(state, TurnOutcome.STOPPED))
        .isEqualTo(
            "{\"rounds\":[[{\"tool\":\"a\",\"outcome\":\"SUCCESS\"},"
                + "{\"tool\":\"a\",\"outcome\":\"FAILED\"},"
                + "{\"tool\":\"b\",\"outcome\":\"SUCCESS\"}]],\"outcome\":\"STOPPED\"}");
  }

  @Test
  void rounds_are_written_in_the_order_they_happened() {
    State state =
        opened()
            .settled(C, CallOutcome.SUCCESS)
            .roundClosed()
            .settled(A, CallOutcome.DENIED)
            .roundClosed();
    assertThat(TurnTrajectory.json(state, TurnOutcome.TRUNCATED))
        .isEqualTo(
            "{\"rounds\":[[{\"tool\":\"c\",\"outcome\":\"SUCCESS\"}],"
                + "[{\"tool\":\"a\",\"outcome\":\"DENIED\"}]],\"outcome\":\"TRUNCATED\"}");
  }

  @Test
  void every_turn_outcome_is_written_by_name() {
    for (TurnOutcome outcome : TurnOutcome.values()) {
      assertThat(TurnTrajectory.json(opened(), outcome))
          .endsWith("\"outcome\":\"" + outcome.name() + "\"}");
    }
  }

  @Test
  void a_lone_surrogate_is_written_as_escape_text_and_flagged() {
    State state = opened().settled(new ToolName("x\uD800y"), CallOutcome.FAILED).roundClosed();
    assertThat(TurnTrajectory.json(state, TurnOutcome.ANSWERED))
        .isEqualTo(
            "{\"rounds\":[[{\"tool\":\"x\\\\uD800y\",\"outcome\":\"FAILED\",\"escaped\":true}]],"
                + "\"outcome\":\"ANSWERED\"}");
  }

  @Test
  void a_nul_is_written_as_escape_text_and_flagged() {
    State state = opened().settled(new ToolName("x\u0000y"), CallOutcome.FAILED).roundClosed();
    assertThat(TurnTrajectory.json(state, TurnOutcome.ANSWERED))
        .contains("\"tool\":\"x\\\\u0000y\"")
        .contains("\"escaped\":true");
  }

  @Test
  void a_well_formed_surrogate_pair_is_written_as_it_is() {
    State state = opened().settled(new ToolName("😀"), CallOutcome.SUCCESS).roundClosed();
    assertThat(TurnTrajectory.json(state, TurnOutcome.ANSWERED))
        .contains("\"tool\":\"😀\"")
        .doesNotContain("escaped");
  }

  @Test
  void a_name_that_merely_spells_an_escape_is_not_flagged() {
    State spelled = opened().settled(new ToolName("x\\uD800y"), CallOutcome.FAILED).roundClosed();
    State lone = opened().settled(new ToolName("x\uD800y"), CallOutcome.FAILED).roundClosed();
    assertThat(TurnTrajectory.json(spelled, TurnOutcome.ANSWERED))
        .doesNotContain("escaped")
        .isNotEqualTo(TurnTrajectory.json(lone, TurnOutcome.ANSWERED));
  }

  @Test
  void two_renderings_are_equal_exactly_when_their_hashes_are() {
    List<State> states = new ArrayList<>();
    states.add(opened());
    states.add(opened().settled(A, CallOutcome.SUCCESS).roundClosed());
    states.add(
        opened().settled(A, CallOutcome.SUCCESS).settled(B, CallOutcome.SUCCESS).roundClosed());
    states.add(
        opened().settled(B, CallOutcome.SUCCESS).settled(A, CallOutcome.SUCCESS).roundClosed());
    states.add(
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .settled(A, CallOutcome.SUCCESS)
            .settled(B, CallOutcome.SUCCESS)
            .roundClosed());
    states.add(
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .settled(B, CallOutcome.SUCCESS)
            .roundClosed()
            .settled(C, CallOutcome.SUCCESS)
            .roundClosed());
    states.add(
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .roundClosed()
            .settled(B, CallOutcome.SUCCESS)
            .settled(C, CallOutcome.SUCCESS)
            .roundClosed());
    states.add(opened().settled(A, CallOutcome.FAILED).roundClosed());
    states.add(opened().settled(A, CallOutcome.DENIED).roundClosed());
    states.add(opened().settled(new ToolName("x\uD800"), CallOutcome.FAILED).roundClosed());
    states.add(opened().settled(new ToolName("x\\uD800"), CallOutcome.FAILED).roundClosed());
    assertThat(states).isNotEmpty();
    for (State left : states) {
      for (State right : states) {
        for (TurnOutcome lo : TurnOutcome.values()) {
          for (TurnOutcome ro : TurnOutcome.values()) {
            boolean sameJson = TurnTrajectory.json(left, lo).equals(TurnTrajectory.json(right, ro));
            boolean sameHash =
                TurnTrajectory.fingerprint(left, lo).equals(TurnTrajectory.fingerprint(right, ro));
            assertThat(sameJson).as("%s/%s vs %s/%s", left, lo, right, ro).isEqualTo(sameHash);
          }
        }
      }
    }
  }
}
