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
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
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

  private static List<AgentEvent> oneRoundThenAnswer(boolean withRetry) {
    List<AgentEvent> events = new ArrayList<>();
    events.add(
        new AgentEvent.TurnStarted(new Seq(1), TURN, PayloadRef.of("p"), "Q", ARRIVED, STARTED));
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
