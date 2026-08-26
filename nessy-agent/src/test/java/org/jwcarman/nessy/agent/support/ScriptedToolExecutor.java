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
package org.jwcarman.nessy.agent.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.api.tool.ComputationCallback;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * Both doors, scripted (approval-lifecycle spec §4): {@code seekApproval} answers in-process —
 * approved unless a denial or a deferral was scripted for that call id — and {@code runTool}
 * answers each call by id with its scripted outcome. Both deliver asynchronously through the pump;
 * {@link #executed()} records {@code runTool} calls only.
 */
public final class ScriptedToolExecutor implements ToolCallExecutor {

  /** Any deadline: these tests are about routing, not about when a wait ends. */
  private static final Instant DEADLINE = Instant.parse("2030-01-01T00:00:00Z");

  /** Any term: the scripted handoff below never clips it. */
  private static final Duration TERM = Duration.ofDays(7);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Executor pump;
  private final Map<String, ToolOutcome> outcomes = new HashMap<>();
  private final Map<String, String> denials = new HashMap<>();
  private final Map<String, ComputationId> deferrals = new HashMap<>();
  private final List<ToolCall> executed = new ArrayList<>();

  public ScriptedToolExecutor(Executor pump) {
    this.pump = pump;
  }

  /** Scripts what {@code runTool} answers for {@code toolCallId}. */
  public void answer(String toolCallId, ToolOutcome outcome) {
    outcomes.put(toolCallId, outcome);
  }

  /** Scripts {@code seekApproval} to deny {@code toolCallId} rather than approve it. */
  public void deny(String toolCallId, String reason) {
    denials.put(toolCallId, reason);
  }

  /** Scripts {@code seekApproval} to park {@code toolCallId} under {@code approval}. */
  public void defer(String toolCallId, ComputationId approval) {
    deferrals.put(toolCallId, approval);
  }

  @Override
  public void seekApproval(ToolCall call, ModelResponseId responseId, Sink sink) {
    if (deferrals.containsKey(call.id())) {
      // The ask only ASKS now (deferral-by-callback spec §9a): no id is minted here, because the
      // scripted one is minted by the handoff door below, exactly as Continuum would mint the real
      // one there.
      pump.execute(
          () ->
              sink.deliver(
                  new AgentEvent.ApprovalDeferralRequested(
                      call, requestFor(call), (id, deadline) -> {}, TERM)));
      return;
    }
    String denied = denials.get(call.id());
    Approval answer = denied == null ? Approval.approved() : Approval.denied(denied);
    pump.execute(
        () -> sink.deliver(new AgentEvent.ApprovalAnswered(call, Optional.empty(), answer)));
  }

  @Override
  public void deferApproval(
      ToolCall call,
      ApprovalRequest request,
      ComputationCallback callback,
      Duration term,
      ModelResponseId responseId,
      Sink sink) {
    ComputationId parked = deferrals.get(call.id());
    if (parked == null) {
      throw new IllegalStateException("no scripted deferral for call " + call.id());
    }
    callback.accept(parked, DEADLINE);
    pump.execute(
        () -> sink.deliver(new AgentEvent.ApprovalDeferred(call, parked, request, DEADLINE)));
  }

  @Override
  public void deferToolCall(
      ToolCall call,
      ComputationCallback callback,
      Duration term,
      ModelResponseId responseId,
      Sink sink) {
    throw new IllegalStateException("this executor scripts no tool deferrals");
  }

  private static ApprovalRequest requestFor(ToolCall call) {
    return ApprovalRequest.draft("scripted", "scripted", call, Map.of(), MAPPER).freeze();
  }

  @Override
  public void runTool(ToolCall call, ModelResponseId responseId, Sink sink) {
    executed.add(call);
    ToolOutcome outcome = outcomes.get(call.id());
    if (outcome == null) {
      throw new IllegalStateException("no scripted outcome for call " + call.id());
    }
    pump.execute(() -> sink.deliver(new AgentEvent.ToolFinished(call, Optional.empty(), outcome)));
  }

  public List<ToolCall> executed() {
    return List.copyOf(executed);
  }
}
