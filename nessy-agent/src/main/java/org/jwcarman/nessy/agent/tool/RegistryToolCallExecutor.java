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
package org.jwcarman.nessy.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.ToolError;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolEvent;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.approval.Adjudication;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.approval.Approver;

/**
 * The registry tool executor (§4.3): find, bind, judge, execute, deliver — and the harness's one
 * authorization chokepoint. Every call passes its grant's {@link ToolGrant#judgment()} before the
 * tool ever runs: a {@link UsagePolicy.Static} grant (Allow/Deny) takes the rung-0 fast path — no
 * action rendered, no context assembled — while every other grant renders the action, runs its
 * enrichers, and lets the policy judge. A {@code RuntimeException} escaping any of that is caught
 * and turned into a fail-closed denial whose message names the stage that broke. {@link
 * PolicyDecision.Deny} and a refused {@link Adjudication} both deliver in-band, narrated, so the
 * model reads the reason and reacts; {@link PolicyDecision.Allow} and a granted {@link
 * Adjudication} run the tool. {@link PolicyDecision.RequireApproval} routes to the wiring's {@link
 * Approver} — the default (5- and 6-arg constructors) refuses loudly in-band, since approval is a
 * capability of the wiring, not a right of every deployment.
 *
 * <p>What happens when a tool defers is the wiring's {@link DeferredToolCallPolicy}: the default
 * (5-arg constructor) fails loudly in-band — a deferred turn wedges a conversation — while a
 * durable wiring suspends the call into a slot. A suspended call, whether from a deferred tool or a
 * suspended {@link Adjudication}, delivers nothing and narrates nothing.
 */
public final class RegistryToolCallExecutor implements ToolCallExecutor {

  private final ToolRegistry registry;
  private final AgentType type;
  private final AgentId id;
  private final TurnObserver turn;
  private final Executor executor;
  private final DeferredToolCallPolicy deferredToolCallPolicy;
  private final Approver approver;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String PARKING_UNAVAILABLE =
      "deferred execution is unavailable in this wiring; the desk arrives with the autonomous host";

  static final String APPROVAL_UNAVAILABLE =
      "approval is unavailable in this wiring; the desk arrives with the autonomous host";

  public RegistryToolCallExecutor(
      ToolRegistry registry, AgentType type, AgentId id, TurnObserver turn, Executor executor) {
    this(registry, type, id, turn, executor, defaultPolicy(turn));
  }

  public RegistryToolCallExecutor(
      ToolRegistry registry,
      AgentType type,
      AgentId id,
      TurnObserver turn,
      Executor executor,
      DeferredToolCallPolicy deferredToolCallPolicy) {
    this(registry, type, id, turn, executor, deferredToolCallPolicy, defaultApprover());
  }

  public RegistryToolCallExecutor(
      ToolRegistry registry,
      AgentType type,
      AgentId id,
      TurnObserver turn,
      Executor executor,
      DeferredToolCallPolicy deferredToolCallPolicy,
      Approver approver) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
    this.deferredToolCallPolicy =
        Objects.requireNonNull(deferredToolCallPolicy, "deferredToolCallPolicy must not be null");
    this.approver = Objects.requireNonNull(approver, "approver must not be null");
  }

  @Override
  public void executeTool(ToolCall call, Sink sink) {
    executor.execute(
        () -> {
          switch (execute(call)) {
            case ToolExecution.Immediate(ToolOutcome outcome) ->
                sink.deliver(new AgentEvent.ToolFinished(call, outcome));
            case ToolExecution.Deferred(_) -> {
              // suspended into its slot: nothing delivered, nothing narrated (§4.3) — the
              // completion re-enters through the slot's registered continuation
            }
          }
        });
  }

  private ToolExecution execute(ToolCall call) {
    Optional<ToolGrant> found = registry.find(call.name());
    if (found.isEmpty()) {
      return new ToolExecution.Immediate(failed(call, "unknown tool: " + call.name()));
    }
    try {
      return invoke(found.get(), call);
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return new ToolExecution.Immediate(failed(call, message));
    }
  }

  private ToolExecution invoke(ToolGrant grant, ToolCall call) {
    Object input = convert(call, grant.tool());
    CallAddress address = new CallAddress(type.name(), id.value(), call.id());
    PolicyDecision decision;
    AuthzContext context = null;
    Object action = null;
    if (grant.policy() instanceof UsagePolicy.Static fixed) {
      decision = fixed.decision(); // rung 0: no action rendered, no context assembled
    } else {
      ToolGrant.Judged judged;
      try {
        judged = grant.judgment().decide(AuthzContext.of(type.name(), call), input);
      } catch (RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new ToolExecution.Immediate(failed(call, "authorization failed: " + message));
      }
      decision = judged.decision();
      context = judged.context();
      action = judged.action();
    }
    return switch (decision) {
      case PolicyDecision.Allow _ -> run(grant.tool(), input, call, address);
      case PolicyDecision.Deny(String reason) -> new ToolExecution.Immediate(failed(call, reason));
      case PolicyDecision.RequireApproval _ ->
          switch (approver.adjudicate(new ApprovalRequest(address, call, action, context))) {
            case Adjudication.Granted _ -> run(grant.tool(), input, call, address);
            case Adjudication.Refused(String reason) ->
                new ToolExecution.Immediate(failed(call, reason));
            case Adjudication.Suspended(var slot) -> new ToolExecution.Deferred(slot);
          };
    };
  }

  private <T> Object convert(ToolCall call, Tool<T> tool) {
    return MAPPER.convertValue(call.arguments(), tool.inputType());
  }

  private <T> ToolExecution run(Tool<T> tool, Object input, ToolCall call, CallAddress address) {
    T typed = tool.inputType().cast(input);
    ToolContext context = new ToolContext(call, event -> narrate(call, event), address);
    return switch (tool.execute(typed, context)) {
      case Awaited.Ready<ToolResult>(ToolResult value) -> {
        turn.on(new TurnEvent.ToolCallCompleted(call, value));
        yield new ToolExecution.Immediate(new ToolOutcome.Returned(value));
      }
      case Awaited.Deferred<ToolResult> _ -> deferredToolCallPolicy.onDeferred(call, address);
    };
  }

  /**
   * Narrates a tool's {@link ToolEvent} onto the turn. The sealed switch is the compile-time
   * contract — a new {@link ToolEvent} variant fails this build until it is handled here.
   */
  private void narrate(ToolCall call, ToolEvent event) {
    switch (event) {
      case ToolEvent.Progress(String message) ->
          turn.on(new TurnEvent.ToolCallProgressed(call, message));
    }
  }

  private ToolOutcome failed(ToolCall call, String message) {
    ToolResult error = ToolResult.error(message);
    turn.on(new TurnEvent.ToolCallCompleted(call, error));
    return new ToolOutcome.Failed(new ToolError(message));
  }

  /** The 5-arg constructor's default: fails loudly in-band rather than suspending silently. */
  private static DeferredToolCallPolicy defaultPolicy(TurnObserver turn) {
    return (call, address) -> {
      ToolResult error = ToolResult.error(PARKING_UNAVAILABLE);
      turn.on(new TurnEvent.ToolCallCompleted(call, error));
      return new ToolExecution.Immediate(
          new ToolOutcome.Failed(new ToolError(PARKING_UNAVAILABLE)));
    };
  }

  /**
   * The 5- and 6-arg constructors' default: approval is a capability of the wiring, not a right of
   * every deployment, so a wiring that never wires an {@link Approver} refuses loudly in-band.
   */
  private static Approver defaultApprover() {
    return request -> new Adjudication.Refused(APPROVAL_UNAVAILABLE);
  }
}
