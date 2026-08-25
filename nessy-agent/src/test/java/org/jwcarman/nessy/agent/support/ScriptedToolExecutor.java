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

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Executor pump;
  private final Map<String, ToolOutcome> outcomes = new HashMap<>();
  private final Map<String, String> denials = new HashMap<>();
  private final Map<String, ComputationId> deferrals = new HashMap<>();
  private final List<ToolCall> executed = new ArrayList<>();

  public ScriptedToolExecutor(Executor pump) {
    this.pump = pump;
  }

  /** Scripts what {@code runTool} answers for {@code callId}. */
  public void answer(String callId, ToolOutcome outcome) {
    outcomes.put(callId, outcome);
  }

  /** Scripts {@code seekApproval} to deny {@code callId} rather than approve it. */
  public void deny(String callId, String reason) {
    denials.put(callId, reason);
  }

  /** Scripts {@code seekApproval} to park {@code callId} under {@code approval}. */
  public void defer(String callId, ComputationId approval) {
    deferrals.put(callId, approval);
  }

  @Override
  public void seekApproval(ToolCall call, ModelResponseId responseId, Sink sink) {
    ComputationId parked = deferrals.get(call.id());
    if (parked != null) {
      ApprovalRequest request =
          ApprovalRequest.draft("scripted", "scripted", call, MAPPER).freeze();
      pump.execute(() -> sink.deliver(new AgentEvent.ApprovalDeferred(call, parked, request)));
      return;
    }
    String denied = denials.get(call.id());
    Approval answer = denied == null ? Approval.approved() : Approval.denied(denied);
    pump.execute(
        () -> sink.deliver(new AgentEvent.ApprovalAnswered(call, Optional.empty(), answer)));
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
