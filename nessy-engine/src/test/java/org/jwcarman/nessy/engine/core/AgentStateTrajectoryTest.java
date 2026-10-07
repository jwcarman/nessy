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
import java.util.Arrays;
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
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.core.TurnTrajectory.CallOutcome;
import org.jwcarman.nessy.inference.Failure;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AgentStateTrajectoryTest {

  private static final TurnId TURN = new TurnId(1);
  private static final ToolName SEARCH = new ToolName("search");
  private static final ToolName READ = new ToolName("read");
  private static final CallId C1 = new CallId("c1");
  private static final CallId C2 = new CallId("c2");
  private static final CallId C3 = new CallId("c3");
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
  private static final Usage USAGE = Usage.of("a-model", 10, 2);
  private static final Instant ARRIVED = Instant.parse("2026-10-07T14:02:01.220Z");
  private static final Instant STARTED = Instant.parse("2026-10-07T14:02:01.231Z");

  private static ObjectNode none() {
    return JsonNodeFactory.instance.objectNode();
  }

  private static AgentEvent.TurnStarted started() {
    return new AgentEvent.TurnStarted(new Seq(1), TURN, PayloadRef.of("p"), "Q", ARRIVED, STARTED);
  }

  private static AgentEvent.ActionsRequested requested(long seq, CallId... calls) {
    return new AgentEvent.ActionsRequested(
        new Seq(seq),
        TURN,
        PayloadRef.of("r"),
        Arrays.stream(calls)
            .<ActionRequest>map(
                c -> new ActionRequest.ToolCall(c, c.equals(C2) ? READ : SEARCH, "does it", KEY))
            .toList(),
        USAGE,
        Optional.empty());
  }

  private static AgentEvent succeeded(long seq, CallId call) {
    return new AgentEvent.ToolSucceeded(new Seq(seq), TURN, call, PayloadRef.of("ok"), "ok", KEY);
  }

  private static AgentEvent failed(long seq, CallId call) {
    return new AgentEvent.ToolFailed(
        new Seq(seq), TURN, call, CallFailure.FAILED, "boom", none(), KEY);
  }

  private static TurnTrajectory.State trajectoryOf(AgentState state) {
    return switch (state) {
      case AgentState.Inferring inferring -> inferring.trajectory();
      case AgentState.AwaitingActions awaiting -> awaiting.trajectory();
      default -> throw new AssertionError("not mid-turn: " + state);
    };
  }

  private static Failure failure() {
    return new Failure.Transient("rate limited");
  }

  @Test
  void a_turn_opens_with_an_empty_trajectory_that_remembers_when_the_input_arrived() {
    AgentState state = AgentState.idle(Seq.NONE).apply(started());
    TurnTrajectory.State trajectory = trajectoryOf(state);
    assertThat(trajectory.completed()).isEmpty();
    assertThat(trajectory.arrivedAt()).isEqualTo(ARRIVED);
  }

  @Test
  void a_round_closes_when_its_last_call_settles_whatever_order_they_settle_in() {
    AgentState base = AgentState.idle(Seq.NONE).apply(started()).apply(requested(2, C1, C2, C3));
    AgentState oneWay = base.apply(succeeded(3, C1)).apply(failed(4, C2)).apply(succeeded(5, C3));
    AgentState other = base.apply(succeeded(3, C3)).apply(succeeded(4, C1)).apply(failed(5, C2));
    Trajectory a = TurnTrajectory.fingerprint(trajectoryOf(oneWay), TurnOutcome.ANSWERED);
    Trajectory b = TurnTrajectory.fingerprint(trajectoryOf(other), TurnOutcome.ANSWERED);
    assertThat(a).isEqualTo(b);
    assertThat(trajectoryOf(oneWay).completed()).hasSize(1);
    assertThat(trajectoryOf(oneWay).completed().getFirst().entries())
        .containsExactly(
            new TurnTrajectory.Entry(READ, CallOutcome.FAILED),
            new TurnTrajectory.Entry(SEARCH, CallOutcome.SUCCESS),
            new TurnTrajectory.Entry(SEARCH, CallOutcome.SUCCESS));
  }

  @Test
  void a_round_still_open_has_not_been_counted_as_a_round() {
    AgentState state =
        AgentState.idle(Seq.NONE)
            .apply(started())
            .apply(requested(2, C1, C2))
            .apply(succeeded(3, C1));
    assertThat(trajectoryOf(state).completed()).isEmpty();
    assertThat(trajectoryOf(state).current()).hasSize(1);
  }

  @Test
  void a_retried_attempt_counts_as_a_retry_and_leaves_the_rounds_alone() {
    AgentState state =
        AgentState.idle(Seq.NONE)
            .apply(started())
            .apply(
                new AgentEvent.InferenceAttempted(
                    new Seq(2), TURN, failure(), USAGE, Optional.empty()));
    assertThat(trajectoryOf(state).retries()).isEqualTo(1);
    assertThat(trajectoryOf(state).completed()).isEmpty();
  }

  @Test
  void deferrals_and_approvals_do_not_touch_the_trajectory() {
    AgentState base = AgentState.idle(Seq.NONE).apply(started()).apply(requested(2, C1));
    AgentState plain = base.apply(succeeded(3, C1));
    AgentState slow =
        base.apply(
                new AgentEvent.ApprovalDeferred(new Seq(3), TURN, C1, Instant.EPOCH, none(), KEY))
            .apply(new AgentEvent.ToolApproved(new Seq(4), TURN, C1, Optional.empty(), none(), KEY))
            .apply(new AgentEvent.ToolDeferred(new Seq(5), TURN, C1, Instant.EPOCH, KEY))
            .apply(succeeded(6, C1));
    assertThat(trajectoryOf(slow)).isEqualTo(trajectoryOf(plain));
  }

  @Test
  void the_trajectory_survives_a_second_round() {
    AgentState state =
        AgentState.idle(Seq.NONE)
            .apply(started())
            .apply(requested(2, C1))
            .apply(succeeded(3, C1))
            .apply(requested(4, C1))
            .apply(succeeded(5, C1));
    assertThat(trajectoryOf(state).completed()).hasSize(2);
  }
}
