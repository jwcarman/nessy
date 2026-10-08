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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
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
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentTurns;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;
import org.jwcarman.nessy.inference.Failure;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TurnRecorderTest {

  private static final AgentType TYPE = new AgentType("research");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final TurnId TURN = new TurnId(1);
  private static final CallId C1 = new CallId("c1");
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
  private static final Usage USAGE = Usage.of("a-model", 10, 2);
  private static final Instant ARRIVED = Instant.parse("2026-10-07T14:02:01.220Z");
  private static final Instant STARTED = Instant.parse("2026-10-07T14:02:01.231Z");
  private static final Instant ENDED = Instant.parse("2026-10-07T14:02:04.018Z");

  private final AgentTurns turns = new InMemoryAgentTurns();

  /** A store that answers a scripted bit and remembers what it was asked, and in what order. */
  private static final class ScriptedTurns implements AgentTurns {
    private final boolean answer;
    private final InMemoryAgentTurns rows = new InMemoryAgentTurns();
    final List<String> calls = new ArrayList<>();

    ScriptedTurns(boolean answer) {
      this.answer = answer;
    }

    @Override
    public boolean firstSighting(AgentType type, String label, Trajectory trajectory, Instant at) {
      calls.add("sight " + label + " " + trajectory.hash() + " " + at);
      return answer;
    }

    @Override
    public void append(AgentType type, AgentId agent, AgentTurn turn) {
      calls.add("append " + turn.turn().value());
      rows.append(type, agent, turn);
    }

    @Override
    public List<AgentTurn> of(AgentType type, AgentId agent) {
      return rows.of(type, agent);
    }
  }

  @Test
  void the_row_carries_the_novelty_the_store_answered() {
    for (boolean answer : List.of(true, false)) {
      TurnRecorder recorder =
          new TurnRecorder(TYPE, new ScriptedTurns(answer), ObservationRegistry.NOOP);
      AgentTurn row =
          recorder
              .recordEnding(AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED)
              .orElseThrow();
      assertThat(row.novel()).isEqualTo(answer);
    }
  }

  @Test
  void the_store_is_asked_once_before_the_row_with_the_rows_label_trajectory_and_end() {
    ScriptedTurns store = new ScriptedTurns(true);
    TurnRecorder recorder = new TurnRecorder(TYPE, store, ObservationRegistry.NOOP);
    AgentTurn row =
        recorder
            .recordEnding(
                AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false, "a\u0000b"), ENDED)
            .orElseThrow();
    assertThat(row.label()).isEqualTo("a\uFFFDb");
    assertThat(store.calls)
        .containsExactly(
            "sight a\uFFFDb " + row.trajectory().hash() + " " + ENDED,
            "append " + row.turn().value());
  }

  @Test
  void the_first_turn_on_a_path_is_novel_and_the_next_is_not() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    AgentTurn first =
        recorder
            .recordEnding(AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED)
            .orElseThrow();
    AgentTurn second =
        recorder
            .recordEnding(
                new AgentId(UUID.randomUUID()),
                AgentState.idle(Seq.NONE),
                oneRoundThenAnswer(false),
                ENDED)
            .orElseThrow();
    assertThat(first.novel()).isTrue();
    assertThat(second.novel()).isFalse();
  }

  @Test
  void events_that_end_no_turn_do_not_ask_the_store_about_a_path() {
    ScriptedTurns store = new ScriptedTurns(true);
    TurnRecorder recorder = new TurnRecorder(TYPE, store, ObservationRegistry.NOOP);
    List<AgentEvent> events = oneRoundThenAnswer(false).subList(0, 3);
    assertThat(recorder.recordEnding(AGENT, AgentState.idle(Seq.NONE), events, ENDED)).isEmpty();
    assertThat(store.calls).isEmpty();
  }

  @Test
  void the_novelty_tag_follows_the_stores_answer() {
    assertThat(novelTagOf(new ScriptedTurns(false))).isEqualTo("false");
    assertThat(novelTagOf(new ScriptedTurns(true))).isEqualTo("true");
  }

  private static String novelTagOf(AgentTurns store) {
    List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
    ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStop(Observation.Context context) {
                stopped.add(context);
              }
            });
    TurnRecorder recorder = new TurnRecorder(TYPE, store, registry);
    Observation.createNotStarted("invoke_agent", registry)
        .observe(
            () ->
                recorder
                    .recordEnding(
                        AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED)
                    .orElseThrow());
    assertThat(stopped).hasSize(1);
    return stopped.getFirst().getHighCardinalityKeyValue("nessy.trajectory.novel").getValue();
  }

  private static List<AgentEvent> oneRoundThenAnswer(boolean withRetry) {
    return oneRoundThenAnswer(withRetry, "Q");
  }

  private static List<AgentEvent> oneRoundThenAnswer(boolean withRetry, String label) {
    List<AgentEvent> events = new ArrayList<>();
    events.add(
        new AgentEvent.TurnStarted(new Seq(1), TURN, PayloadRef.of("p"), label, ARRIVED, STARTED));
    long seq = 2;
    if (withRetry) {
      events.add(
          new AgentEvent.InferenceAttempted(
              new Seq(seq++), TURN, new Failure.Transient("busy"), USAGE, Optional.empty()));
    }
    events.add(
        new AgentEvent.ActionsRequested(
            new Seq(seq++),
            TURN,
            PayloadRef.of("r"),
            List.of(new ActionRequest.ToolCall(C1, new ToolName("search"), "does it", KEY)),
            USAGE,
            Optional.empty()));
    events.add(
        new AgentEvent.ToolSucceeded(new Seq(seq++), TURN, C1, PayloadRef.of("ok"), "ok", KEY));
    events.add(
        new AgentEvent.InferenceAnswered(
            new Seq(seq), TURN, PayloadRef.of("a"), false, USAGE, Optional.empty()));
    return events;
  }

  @Test
  void the_row_carries_the_readable_trajectory_of_its_turn() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    AgentTurn row =
        recorder
            .recordEnding(AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED)
            .orElseThrow();
    assertThat(row.trajectoryJson())
        .isEqualTo(
            "{\"rounds\":[[{\"tool\":\"search\",\"outcome\":\"SUCCESS\"}]],\"outcome\":\"ANSWERED\"}");
  }

  @Test
  void the_row_carries_the_label_the_turn_started_with() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    AgentTurn row =
        recorder
            .recordEnding(
                AGENT,
                AgentState.idle(Seq.NONE),
                oneRoundThenAnswer(false, "invoice:PRICE_VARIANCE"),
                ENDED)
            .orElseThrow();
    assertThat(row.label()).isEqualTo("invoice:PRICE_VARIANCE");
  }

  @Test
  void the_same_behavior_under_two_labels_is_one_trajectory() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    AgentTurn first =
        recorder
            .recordEnding(
                AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false, "invoice"), ENDED)
            .orElseThrow();
    AgentTurn second =
        recorder
            .recordEnding(
                new AgentId(UUID.randomUUID()),
                AgentState.idle(Seq.NONE),
                oneRoundThenAnswer(false, "receipt"),
                ENDED)
            .orElseThrow();
    assertThat(second.trajectory()).isEqualTo(first.trajectory());
    assertThat(second.trajectoryJson()).isEqualTo(first.trajectoryJson());
    assertThat(second.label()).isNotEqualTo(first.label());
  }

  @Test
  void a_label_the_database_cannot_hold_is_made_safe_on_the_row() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    AgentTurn row =
        recorder
            .recordEnding(
                AGENT,
                AgentState.idle(Seq.NONE),
                oneRoundThenAnswer(false, "a\u0000b\uD800c"),
                ENDED)
            .orElseThrow();
    assertThat(row.label()).isEqualTo("a\uFFFDb\uFFFDc");
  }

  @Test
  void a_valid_surrogate_pair_is_kept_and_each_lone_half_is_replaced() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    String label = "x\uD83D\uDE00y\uDE00z\uD800";
    AgentTurn row =
        recorder
            .recordEnding(AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false, label), ENDED)
            .orElseThrow();
    assertThat(row.label()).isEqualTo("x\uD83D\uDE00y\uFFFDz\uFFFD");
  }

  @Test
  void every_shape_of_turn_leaves_a_row_whose_counts_agree_with_its_json() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    List<AgentEvent> noTools =
        List.of(
            new AgentEvent.TurnStarted(new Seq(1), TURN, PayloadRef.of("p"), "Q", ARRIVED, STARTED),
            new AgentEvent.InferenceAnswered(
                new Seq(2), TURN, PayloadRef.of("a"), false, USAGE, Optional.empty()));
    List<AgentEvent> stopped = new ArrayList<>(oneRoundThenAnswer(false).subList(0, 3));
    stopped.add(new AgentEvent.TurnStopped(new Seq(4), TURN, "enough"));
    List<List<AgentEvent>> shapes =
        List.of(noTools, oneRoundThenAnswer(false), twoRoundsMixed(), stopped);
    List<AgentTurn> rows = new ArrayList<>();
    for (List<AgentEvent> shape : shapes) {
      rows.add(
          recorder
              .recordEnding(new AgentId(UUID.randomUUID()), AgentState.idle(Seq.NONE), shape, ENDED)
              .orElseThrow());
    }
    assertThat(rows).hasSize(4);
    assertThat(rows.get(2).rounds()).isEqualTo(2);
    assertThat(rows.get(2).toolDenials()).isEqualTo(1);
    rows.forEach(TurnRowConsistency::assertConsistent);
  }

  private static List<AgentEvent> twoRoundsMixed() {
    CallId c2 = new CallId("c2");
    CallId c3 = new CallId("c3");
    CallId c4 = new CallId("c4");
    return List.of(
        new AgentEvent.TurnStarted(new Seq(1), TURN, PayloadRef.of("p"), "Q", ARRIVED, STARTED),
        new AgentEvent.ActionsRequested(
            new Seq(2),
            TURN,
            PayloadRef.of("r"),
            List.of(
                new ActionRequest.ToolCall(C1, new ToolName("search"), "a", KEY),
                new ActionRequest.ToolCall(c2, new ToolName("read"), "b", KEY),
                new ActionRequest.ToolCall(c3, new ToolName("fetch"), "c", KEY)),
            USAGE,
            Optional.empty()),
        new AgentEvent.ToolSucceeded(new Seq(3), TURN, C1, PayloadRef.of("ok"), "ok", KEY),
        new AgentEvent.ToolFailed(new Seq(4), TURN, c2, CallFailure.FAILED, "broke", null, KEY),
        new AgentEvent.ToolDenied(new Seq(5), TURN, c3, "no", Optional.empty(), null, KEY),
        new AgentEvent.ActionsRequested(
            new Seq(6),
            TURN,
            PayloadRef.of("r2"),
            List.of(new ActionRequest.ToolCall(c4, new ToolName("search"), "d", KEY)),
            USAGE,
            Optional.empty()),
        new AgentEvent.ToolSucceeded(new Seq(7), TURN, c4, PayloadRef.of("ok"), "ok", KEY),
        new AgentEvent.InferenceAnswered(
            new Seq(8), TURN, PayloadRef.of("a"), false, USAGE, Optional.empty()));
  }

  @Test
  void a_turn_that_ends_gets_one_row_bounded_by_its_first_and_last_seq() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    List<AgentEvent> events = oneRoundThenAnswer(false);
    Optional<AgentTurn> recorded =
        recorder.recordEnding(AGENT, AgentState.idle(Seq.NONE), events, ENDED);
    assertThat(recorded).isPresent();
    AgentTurn row = recorded.get();
    assertThat(row.turn()).isEqualTo(TURN);
    assertThat(row.endingSeq()).isEqualTo(new Seq(4));
    assertThat(row.arrivedAt()).isEqualTo(ARRIVED);
    assertThat(row.startedAt()).isEqualTo(STARTED);
    assertThat(row.endedAt()).isEqualTo(ENDED);
    assertThat(row.outcome()).isEqualTo(TurnOutcome.ANSWERED);
    assertThat(row.rounds()).isEqualTo(1);
    assertThat(row.toolCalls()).isEqualTo(1);
    assertThat(row.toolSuccesses()).isEqualTo(1);
    assertThat(row.inferenceCalls()).isEqualTo(2); // the request and the answer
    assertThat(row.inferenceRetries()).isZero();
    assertThat(turns.of(TYPE, AGENT)).containsExactly(row);
    TurnRowConsistency.assertConsistent(row);
  }

  @Test
  void a_retry_is_counted_as_a_call_and_a_retry_and_does_not_change_the_trajectory() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    AgentTurn plain =
        recorder
            .recordEnding(AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED)
            .orElseThrow();
    AgentTurn retried =
        recorder
            .recordEnding(
                new AgentId(UUID.randomUUID()),
                AgentState.idle(Seq.NONE),
                oneRoundThenAnswer(true),
                ENDED)
            .orElseThrow();
    assertThat(retried.trajectory()).isEqualTo(plain.trajectory());
    TurnRowConsistency.assertConsistent(plain);
    TurnRowConsistency.assertConsistent(retried);
    assertThat(retried.inferenceCalls()).isEqualTo(3);
    assertThat(retried.inferenceRetries()).isEqualTo(1);
  }

  @Test
  void events_that_end_no_turn_record_nothing() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    List<AgentEvent> events = oneRoundThenAnswer(false).subList(0, 3);
    assertThat(recorder.recordEnding(AGENT, AgentState.idle(Seq.NONE), events, ENDED)).isEmpty();
    assertThat(turns.of(TYPE, AGENT)).isEmpty();
  }

  @Test
  void a_policy_stop_after_the_last_call_counts_the_round_and_ends_stopped() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    List<AgentEvent> events = new ArrayList<>(oneRoundThenAnswer(false).subList(0, 3));
    events.add(new AgentEvent.TurnStopped(new Seq(4), TURN, "enough"));
    AgentTurn row =
        recorder.recordEnding(AGENT, AgentState.idle(Seq.NONE), events, ENDED).orElseThrow();
    TurnRowConsistency.assertConsistent(row);
    assertThat(row.outcome()).isEqualTo(TurnOutcome.STOPPED);
    assertThat(row.rounds()).isEqualTo(1);
    assertThat(row.inferenceCalls()).isEqualTo(1);
  }

  @Test
  void a_failed_turn_counts_the_failing_call_and_no_retry() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    List<AgentEvent> events = new ArrayList<>(oneRoundThenAnswer(false).subList(0, 3));
    events.add(
        new AgentEvent.InferenceFailed(
            new Seq(4), TURN, new Failure.Permanent("no"), USAGE, Optional.empty()));
    AgentTurn row =
        recorder.recordEnding(AGENT, AgentState.idle(Seq.NONE), events, ENDED).orElseThrow();
    TurnRowConsistency.assertConsistent(row);
    assertThat(row.outcome()).isEqualTo(TurnOutcome.FAILED);
    assertThat(row.inferenceCalls()).isEqualTo(2);
    assertThat(row.inferenceRetries()).isZero();
  }

  @Test
  void the_live_fold_and_a_replay_of_the_same_events_agree() {
    List<AgentEvent> events = oneRoundThenAnswer(true);
    TurnRecorder live = new TurnRecorder(TYPE, new InMemoryAgentTurns(), ObservationRegistry.NOOP);
    // Live: the events arrive in two decisions, each folded onto the state the last left behind.
    AgentState after = AgentState.idle(Seq.NONE).applyAll(events.subList(0, 3));
    AgentTurn liveRow =
        live.recordEnding(AGENT, after, events.subList(3, events.size()), ENDED).orElseThrow();
    // Replay: the whole slice from idle, as reconstitute would fold it.
    TurnRecorder replay =
        new TurnRecorder(TYPE, new InMemoryAgentTurns(), ObservationRegistry.NOOP);
    AgentTurn replayRow =
        replay.recordEnding(AGENT, AgentState.idle(Seq.NONE), events, ENDED).orElseThrow();
    TurnRowConsistency.assertConsistent(liveRow);
    TurnRowConsistency.assertConsistent(replayRow);
    assertThat(replayRow).isEqualTo(liveRow);
  }

  @Test
  void the_current_observation_is_tagged_with_the_trajectory() {
    List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
    ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStop(Observation.Context context) {
                stopped.add(context);
              }
            });
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, registry);
    AgentTurn row =
        Observation.createNotStarted("invoke_agent", registry)
            .observe(
                () ->
                    recorder
                        .recordEnding(
                            AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED)
                        .orElseThrow());
    assertThat(stopped).hasSize(1);
    Observation.Context context = stopped.getFirst();
    assertThat(context.getHighCardinalityKeyValue("nessy.trajectory.hash").getValue())
        .isEqualTo(row.trajectory().hash());
    assertThat(context.getHighCardinalityKeyValue("nessy.trajectory.version").getValue())
        .isEqualTo("1");
    assertThat(context.getHighCardinalityKeyValue("nessy.trajectory.novel").getValue())
        .isEqualTo("true");
    assertThat(context.getHighCardinalityKeyValue("nessy.turn.outcome").getValue())
        .isEqualTo("ANSWERED");
    assertThat(context.getHighCardinalityKeyValue("nessy.turn.rounds").getValue()).isEqualTo("1");
    assertThat(context.getHighCardinalityKeyValue("nessy.turn.tool_calls").getValue())
        .isEqualTo("1");
    assertThat(context.getHighCardinalityKeyValue("nessy.turn.tool_failures").getValue())
        .isEqualTo("0");
    assertThat(context.getHighCardinalityKeyValue("nessy.turn.tool_denials").getValue())
        .isEqualTo("0");
  }

  @Test
  void with_no_observation_in_force_nothing_is_tagged_and_the_row_is_still_written() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.create());
    assertThat(
            recorder.recordEnding(
                AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED))
        .isPresent();
  }
}
