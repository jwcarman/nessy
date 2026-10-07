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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.core.TurnTrajectory.CallOutcome;
import org.jwcarman.nessy.engine.core.TurnTrajectory.State;
import org.jwcarman.nessy.inference.Failure;
import tools.jackson.databind.node.JsonNodeFactory;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TurnTrajectoryTest {

  private static final ToolName A = new ToolName("a");
  private static final ToolName B = new ToolName("b");
  private static final ToolName C = new ToolName("c");
  private static final TurnId TURN = new TurnId(1);
  private static final CallId CALL = new CallId("c1");
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
  private static final Usage USAGE = Usage.of("a-model", 1, 1);

  private static State opened() {
    return State.opened(Instant.EPOCH);
  }

  private static Trajectory of(State state) {
    return TurnTrajectory.fingerprint(state, TurnOutcome.ANSWERED);
  }

  @Test
  void order_within_a_round_does_not_matter() {
    State ab =
        opened().settled(A, CallOutcome.SUCCESS).settled(B, CallOutcome.SUCCESS).roundClosed();
    State ba =
        opened().settled(B, CallOutcome.SUCCESS).settled(A, CallOutcome.SUCCESS).roundClosed();
    assertThat(of(ab)).isEqualTo(of(ba));
  }

  @Test
  void how_many_times_a_tool_ran_in_a_round_does_matter() {
    State aab =
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .settled(A, CallOutcome.SUCCESS)
            .settled(B, CallOutcome.SUCCESS)
            .roundClosed();
    State ab =
        opened().settled(A, CallOutcome.SUCCESS).settled(B, CallOutcome.SUCCESS).roundClosed();
    assertThat(of(aab)).isNotEqualTo(of(ab));
  }

  @Test
  void round_boundaries_matter() {
    State ab_c =
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .settled(B, CallOutcome.SUCCESS)
            .roundClosed()
            .settled(C, CallOutcome.SUCCESS)
            .roundClosed();
    State a_bc =
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .roundClosed()
            .settled(B, CallOutcome.SUCCESS)
            .settled(C, CallOutcome.SUCCESS)
            .roundClosed();
    assertThat(of(ab_c)).isNotEqualTo(of(a_bc));
  }

  @Test
  void the_same_rounds_ending_five_ways_are_five_trajectories() {
    State state = opened().settled(A, CallOutcome.SUCCESS).roundClosed();
    List<Trajectory> all =
        List.of(
            TurnTrajectory.fingerprint(state, TurnOutcome.ANSWERED),
            TurnTrajectory.fingerprint(state, TurnOutcome.TRUNCATED),
            TurnTrajectory.fingerprint(state, TurnOutcome.REFUSED),
            TurnTrajectory.fingerprint(state, TurnOutcome.FAILED),
            TurnTrajectory.fingerprint(state, TurnOutcome.STOPPED));
    assertThat(all).doesNotHaveDuplicates();
  }

  @Test
  void the_outcome_of_a_call_is_part_of_its_entry() {
    State ok = opened().settled(A, CallOutcome.SUCCESS).roundClosed();
    State failed = opened().settled(A, CallOutcome.FAILED).roundClosed();
    State denied = opened().settled(A, CallOutcome.DENIED).roundClosed();
    assertThat(List.of(of(ok), of(failed), of(denied))).doesNotHaveDuplicates();
  }

  @Test
  void a_retry_changes_the_count_and_not_the_trajectory() {
    State plain = opened().settled(A, CallOutcome.SUCCESS).roundClosed();
    State retried = opened().retried().settled(A, CallOutcome.SUCCESS).roundClosed();
    assertThat(of(retried)).isEqualTo(of(plain));
    assertThat(retried.retries()).isEqualTo(1);
  }

  @Test
  void a_turn_with_no_rounds_has_a_trajectory_of_its_own() {
    assertThat(of(opened()))
        .isNotEqualTo(of(opened().settled(A, CallOutcome.SUCCESS).roundClosed()));
    assertThat(of(opened()).hash()).hasSize(64);
  }

  @Test
  void entries_sort_by_utf8_bytes_not_by_utf16_code_units() {
    // U+FF21 (fullwidth A) is one UTF-16 unit, 0xFF21, which sorts AFTER U+1F600 (two units,
    // 0xD83D 0xDE00) by String.compareTo; by UTF-8 bytes (EF BC A1 vs F0 9F 98 80) it sorts BEFORE.
    ToolName fullwidth = new ToolName("Ａ");
    ToolName emoji = new ToolName("😀");
    State state =
        opened()
            .settled(emoji, CallOutcome.SUCCESS)
            .settled(fullwidth, CallOutcome.SUCCESS)
            .roundClosed();
    byte[] canonical = TurnTrajectory.canonical(state, TurnOutcome.ANSWERED);
    int first = indexOf(canonical, fullwidth.value().getBytes(StandardCharsets.UTF_8));
    int second = indexOf(canonical, emoji.value().getBytes(StandardCharsets.UTF_8));
    assertThat(first).isLessThan(second);
  }

  @Test
  void the_canonical_bytes_are_the_documented_framing() {
    State state = opened().settled(A, CallOutcome.FAILED).roundClosed();
    byte[] canonical = TurnTrajectory.canonical(state, TurnOutcome.STOPPED);
    byte[] expected = {
      'N',
      'E',
      'S',
      'S',
      'Y',
      '_',
      'T',
      'R',
      'A',
      'J',
      'E',
      'C',
      'T',
      'O',
      'R',
      'Y',
      0,
      1, // version
      0,
      0,
      0,
      1, // one round
      0,
      0,
      0,
      1, // one entry
      0,
      0,
      0,
      1,
      'a', // name "a"
      2, // FAILED
      (byte) 0xFF, // terminal marker
      5 // STOPPED
    };
    assertThat(canonical).isEqualTo(expected);
  }

  @Test
  void a_settled_call_outside_a_round_is_counted_only_when_the_round_closes() {
    State open = opened().settled(A, CallOutcome.SUCCESS);
    assertThat(open.completed()).isEmpty();
    assertThat(open.toolCalls()).isEqualTo(1);
    assertThat(open.roundClosed().completed()).hasSize(1);
  }

  @Test
  void closing_an_empty_round_is_refused() {
    State state = opened();
    assertThatThrownBy(state::roundClosed).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void the_counts_add_up() {
    State state =
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .settled(B, CallOutcome.FAILED)
            .settled(C, CallOutcome.DENIED)
            .settled(A, CallOutcome.SUCCESS)
            .roundClosed();
    assertThat(state.toolCalls()).isEqualTo(4);
    assertThat(state.count(CallOutcome.SUCCESS)).isEqualTo(2);
    assertThat(state.count(CallOutcome.FAILED)).isEqualTo(1);
    assertThat(state.count(CallOutcome.DENIED)).isEqualTo(1);
  }

  @Test
  void the_five_ways_a_call_settles_are_three_outcomes() {
    var facts = JsonNodeFactory.instance.objectNode();
    assertThat(
            TurnTrajectory.outcomeOf(
                new AgentEvent.ToolSucceeded(new Seq(3), TURN, CALL, PayloadRef.of("r"), "r", KEY)))
        .isEqualTo(CallOutcome.SUCCESS);
    assertThat(
            TurnTrajectory.outcomeOf(
                new AgentEvent.ToolFailed(
                    new Seq(3), TURN, CALL, CallFailure.FAILED, "m", facts, KEY)))
        .isEqualTo(CallOutcome.FAILED);
    assertThat(
            TurnTrajectory.outcomeOf(
                new AgentEvent.ToolFailed(
                    new Seq(3), TURN, CALL, CallFailure.PAST_DEADLINE, "m", facts, KEY)))
        .isEqualTo(CallOutcome.FAILED);
    assertThat(
            TurnTrajectory.outcomeOf(
                new AgentEvent.ToolFailed(
                    new Seq(3), TURN, CALL, CallFailure.NOT_AUTHORISED, "m", facts, KEY)))
        .isEqualTo(CallOutcome.DENIED);
    assertThat(
            TurnTrajectory.outcomeOf(
                new AgentEvent.ToolDenied(
                    new Seq(3), TURN, CALL, "no", Optional.empty(), facts, KEY)))
        .isEqualTo(CallOutcome.DENIED);
  }

  @Test
  void the_four_ending_events_are_five_outcomes_and_nothing_else_ends_a_turn() {
    assertThat(
            TurnTrajectory.endingOf(
                new AgentEvent.InferenceAnswered(
                    new Seq(2), TURN, PayloadRef.of("a"), false, USAGE, Optional.empty())))
        .contains(TurnOutcome.ANSWERED);
    assertThat(
            TurnTrajectory.endingOf(
                new AgentEvent.InferenceAnswered(
                    new Seq(2), TURN, PayloadRef.of("a"), true, USAGE, Optional.empty())))
        .contains(TurnOutcome.TRUNCATED);
    assertThat(
            TurnTrajectory.endingOf(
                new AgentEvent.InferenceRefused(
                    new Seq(2), TURN, "safety", USAGE, Optional.empty())))
        .contains(TurnOutcome.REFUSED);
    assertThat(
            TurnTrajectory.endingOf(
                new AgentEvent.InferenceFailed(
                    new Seq(2), TURN, new Failure.Permanent("no"), USAGE, Optional.empty())))
        .contains(TurnOutcome.FAILED);
    assertThat(TurnTrajectory.endingOf(new AgentEvent.TurnStopped(new Seq(2), TURN, "enough")))
        .contains(TurnOutcome.STOPPED);
    assertThat(
            TurnTrajectory.endingOf(
                new AgentEvent.TurnStarted(
                    new Seq(1), TURN, PayloadRef.of("p"), "Q", Instant.EPOCH, Instant.EPOCH)))
        .isEmpty();
    assertThat(TurnTrajectory.endingOf(new AgentEvent.Terminated(new Seq(9)))).isEmpty();
  }

  @Test
  void a_tool_name_the_model_invented_at_any_length_still_fingerprints() {
    ToolName huge = new ToolName("x".repeat(70_000));
    State state = opened().settled(huge, CallOutcome.FAILED).roundClosed();
    assertThat(of(state).hash()).hasSize(64);
  }

  @Test
  void two_distinct_names_do_not_share_a_fingerprint() {
    State lone = opened().settled(new ToolName("a\uD800"), CallOutcome.FAILED).roundClosed();
    State mark = opened().settled(new ToolName("a?"), CallOutcome.FAILED).roundClosed();
    assertThat(of(lone)).isNotEqualTo(of(mark));
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}
