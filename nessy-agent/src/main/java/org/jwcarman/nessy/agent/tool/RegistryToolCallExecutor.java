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
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.ToolError;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.codec.Codecs;
import org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.computation.ComputationId;
import org.jwcarman.nessy.api.computation.ToolInvocationId;
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
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The registry tool executor (§4.3): find, bind, judge, execute, deliver — and the harness's one
 * authorization chokepoint. Every call passes its grant's {@link ToolGrant#assemble(AuthzContext,
 * Object)} and {@link ToolGrant#decide(AuthzContext)} before the tool ever runs: a {@link
 * UsagePolicy.Static} grant (Allow/Deny) takes the rung-0 fast path — no action rendered, no
 * context assembled — while every other grant renders the action, runs its enrichers, and lets the
 * policy judge the assembled context. A {@code RuntimeException} escaping any of that is caught and
 * turned into a fail-closed denial whose message names the stage that broke. {@link
 * PolicyDecision.Deny} and a refused {@link Adjudication} both deliver in-band, narrated, so the
 * model reads the reason and reacts; {@link PolicyDecision.Allow} and a granted {@link
 * Adjudication} run the tool. {@link PolicyDecision.RequireApproval} routes to the wiring's {@link
 * Approver} — the default (5- and 6-arg constructors) refuses loudly in-band, since approval is a
 * capability of the wiring, not a right of every deployment.
 *
 * <p>What happens when a tool defers is the wiring's {@link DeferredToolCallPolicy}: the default
 * (5-arg constructor) fails loudly in-band — a deferred turn wedges a conversation — while a
 * durable wiring suspends the call into its computation. A suspended call, whether from a deferred
 * tool or a suspended {@link Adjudication}, delivers nothing and narrates nothing.
 *
 * <p>The policy runs inline, exactly once, before the tool ever gets a chance to do anything
 * (durable-deliveries spec §5a). Before that, even before the policy: {@link #gate} checks {@link
 * DeferredToolCallPolicy#pendingComputation} — ownership-split absorption, spec §5a/§6. {@link
 * #executeGrantedToolNow} is the one door that skips both the absorption check and the policy: it
 * is reached only for work the gate already cleared — an approval's granted tool call, or the
 * reaper's redispatch of a {@code RETRYABLE} overdue computation — so re-running policy or
 * re-asking an approver there would be a bug, not a safety net.
 */
public final class RegistryToolCallExecutor implements ToolCallExecutor {

  private final ToolRegistry registry;
  private final AgentType type;
  private final AgentId id;
  private final TurnObserver turn;
  private final Executor executor;
  private final DeferredToolCallPolicy deferredToolCallPolicy;
  private final Approver approver;
  private final Codecs codecs;

  private static final String PARKING_UNAVAILABLE =
      "deferred execution is unavailable in this wiring; the desk arrives with the harness";

  static final String APPROVAL_UNAVAILABLE =
      "approval is unavailable in this wiring; the desk arrives with the harness";

  public RegistryToolCallExecutor(
      ToolRegistry registry,
      AgentType type,
      AgentId id,
      TurnObserver turn,
      Executor executor,
      ObjectMapper mapper) {
    this(registry, type, id, turn, executor, defaultPolicy(turn), mapper);
  }

  public RegistryToolCallExecutor(
      ToolRegistry registry,
      AgentType type,
      AgentId id,
      TurnObserver turn,
      Executor executor,
      DeferredToolCallPolicy deferredToolCallPolicy,
      ObjectMapper mapper) {
    this(registry, type, id, turn, executor, deferredToolCallPolicy, defaultApprover(), mapper);
  }

  public RegistryToolCallExecutor(
      ToolRegistry registry,
      AgentType type,
      AgentId id,
      TurnObserver turn,
      Executor executor,
      DeferredToolCallPolicy deferredToolCallPolicy,
      Approver approver,
      ObjectMapper mapper) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
    this.deferredToolCallPolicy =
        Objects.requireNonNull(deferredToolCallPolicy, "deferredToolCallPolicy must not be null");
    this.approver = Objects.requireNonNull(approver, "approver must not be null");
    this.codecs = new Codecs(Objects.requireNonNull(mapper, "mapper must not be null"));
  }

  @Override
  public void executeTool(ToolCall call, ModelResponseId responseId, Sink sink) {
    Objects.requireNonNull(responseId, "responseId must not be null");
    executor.execute(
        () -> {
          switch (execute(call, responseId)) {
            case ToolExecution.Immediate(ToolOutcome outcome) ->
                sink.deliver(new AgentEvent.ToolFinished(call, outcome));
            case ToolExecution.Deferred(_) -> {
              // suspended into its computation: nothing delivered, nothing narrated (§4.3) — the
              // completion re-enters through the computation's registered continuation
            }
          }
        });
  }

  @Override
  public ToolExecution executeGrantedToolNow(
      ToolCall call,
      CallAddress address,
      ToolInvocationId invocation,
      Optional<Substrate.Op> alsoCommit) {
    return executePastGate(call, address, invocation, alsoCommit);
  }

  private ToolExecution execute(ToolCall call, ModelResponseId responseId) {
    Optional<ToolGrant> found = registry.find(call.name());
    if (found.isEmpty()) {
      return new ToolExecution.Immediate(failed(call, "unknown tool: " + call.name()));
    }
    CallAddress address = new CallAddress(type.name(), id.value(), responseId.value(), call.id());
    try {
      return gate(found.get(), call, address);
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return new ToolExecution.Immediate(failed(call, message));
    }
  }

  private ToolExecution executePastGate(
      ToolCall call,
      CallAddress address,
      ToolInvocationId invocation,
      Optional<Substrate.Op> alsoCommit) {
    Optional<ToolGrant> found = registry.find(call.name());
    if (found.isEmpty()) {
      return new ToolExecution.Immediate(failed(call, "unknown tool: " + call.name()));
    }
    try {
      ToolGrant grant = found.get();
      Object input = convert(call, grant.tool());
      return run(grant.tool(), input, call, address, invocation, alsoCommit);
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return new ToolExecution.Immediate(failed(call, message));
    }
  }

  private ToolExecution gate(ToolGrant grant, ToolCall call, CallAddress address) {
    Optional<ComputationId> pending = deferredToolCallPolicy.pendingComputation(address);
    if (pending.isPresent()) {
      // ownership-split absorption (spec §5a, §6): a staleness redrive reached a call whose
      // approval is still pending, or whose tool computation already exists — the ask, or the
      // work, is already in flight from an earlier pass through this exact gate. Absorb here,
      // before the policy (which could be non-constant) or its enrichers run again, before the
      // tool runs again, and before the approver is ever asked again.
      return new ToolExecution.Deferred(pending.get());
    }
    Object input = convert(call, grant.tool());
    ToolInvocationId invocation = new ToolInvocationId(address.responseId(), call.id());
    PolicyDecision decision;
    AuthzContext assembled = null;
    if (grant.policy() instanceof UsagePolicy.Static fixed) {
      decision = fixed.decision(); // rung 0: no action rendered, no context assembled
    } else {
      try {
        assembled = grant.assemble(AuthzContext.of(type.name(), call), input);
        decision = grant.decide(assembled);
      } catch (RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new ToolExecution.Immediate(failed(call, "authorization failed: " + message));
      }
    }
    return switch (decision) {
      case PolicyDecision.Allow _ ->
          run(grant.tool(), input, call, address, invocation, Optional.empty());
      case PolicyDecision.Deny(String reason) -> new ToolExecution.Immediate(failed(call, reason));
      case PolicyDecision.RequireApproval _ ->
          switch (approver.adjudicate(new ApprovalRequest(address, call, assembled))) {
            case Adjudication.Granted _ ->
                run(grant.tool(), input, call, address, invocation, Optional.empty());
            case Adjudication.Refused(String reason) ->
                new ToolExecution.Immediate(failed(call, reason));
            case Adjudication.Suspended(var computation) -> new ToolExecution.Deferred(computation);
          };
    };
  }

  /**
   * Jackson binds directly — its own polymorphic machinery reads whatever {@code @JsonTypeInfo}/
   * {@code @JsonSubTypes} annotations a sealed input type carries (substrate spec §7, the
   * 2026-08-22 repeal), so the shape {@link org.jwcarman.nessy.api.tool.Schemas} showed the model
   * and the shape bound here agree by construction. {@link Codecs#bind} wraps Jackson's checked
   * exceptions into an {@link IllegalArgumentException} naming the offense — malformed arguments or
   * an unrecognized discriminator surface in-band rather than escaping raw.
   */
  private <T> Object convert(ToolCall call, Tool<T> tool) {
    return codecs.bind(call.arguments(), tool.inputType(), tool.inputType().getSimpleName());
  }

  private <T> ToolExecution run(
      Tool<T> tool,
      Object input,
      ToolCall call,
      CallAddress address,
      ToolInvocationId invocation,
      Optional<Substrate.Op> alsoCommit) {
    T typed = tool.inputType().cast(input);
    ToolContext context = new ToolContext(call, event -> narrate(call, event), address, invocation);
    return switch (tool.execute(typed, context)) {
      case Awaited.Ready<ToolResult>(ToolResult value) -> {
        turn.on(new TurnEvent.ToolCallCompleted(call, value));
        yield new ToolExecution.Immediate(new ToolOutcome.Returned(value));
      }
      case Awaited.Deferred<ToolResult> _ ->
          deferredToolCallPolicy.onDeferred(
              call, address, invocation, tool.retrySemantics(), tool.timeout(), alsoCommit);
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
    return (call, address, invocation, retrySemantics, timeout, alsoCommit) -> {
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
