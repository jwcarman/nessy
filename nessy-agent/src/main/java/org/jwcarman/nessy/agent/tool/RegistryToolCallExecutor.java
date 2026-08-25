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
import org.jwcarman.nessy.agent.CallAddress;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.ToolError;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.codec.Codecs;
import org.jwcarman.nessy.agent.spi.ApprovalContexts;
import org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolEvent;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalContext;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * The registry tool executor: two doors, neither with a conditional inside (approval-lifecycle spec
 * §4).
 *
 * <p>{@link #seekApproval} is the harness's one authorization chokepoint: find the grant, answer a
 * {@link Approvers.Static} approver without building a request at all (the rung-0 fast path — no
 * enricher runs for a call nobody will read the file of), otherwise build the frozen {@link
 * ApprovalRequest} through the grant's contributor and enrichers and let the approver read it. A
 * {@code RuntimeException} escaping conversion, the contributor or an enricher becomes a
 * fail-closed denial naming the stage; a {@code RuntimeException} escaping the approver becomes a
 * denial too. A deferral has already parked and folded itself through {@link
 * ApprovalContext#defer()} — there is nothing left to deliver, and nothing is narrated to the
 * model.
 *
 * <p>{@link #runTool} is past the gate: find, bind, run. It never consults an approver — the answer
 * is already a fact in the phase. What happens when a tool defers is the wiring's {@link
 * DeferredToolCallPolicy}: the default (5-arg constructor) fails loudly in-band — a deferred turn
 * wedges a conversation — while a durable wiring suspends the call into its computation and the
 * executor delivers {@code ToolDeferred} with its id.
 */
public final class RegistryToolCallExecutor implements ToolCallExecutor {

  private final ToolRegistry registry;
  private final AgentType type;
  private final AgentId id;
  private final TurnObserver turn;
  private final Executor executor;
  private final DeferredToolCallPolicy deferredToolCallPolicy;
  private final ApprovalContexts approvalContexts;
  private final Codecs codecs;
  private final ObjectMapper mapper;

  private static final String PARKING_UNAVAILABLE =
      "deferred execution is unavailable in this wiring; the desk arrives with the harness";

  static final String APPROVAL_UNAVAILABLE =
      "approval parking is unavailable in this wiring; the desk arrives with the harness";

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
    this(
        registry,
        type,
        id,
        turn,
        executor,
        deferredToolCallPolicy,
        defaultApprovalContexts(),
        mapper);
  }

  public RegistryToolCallExecutor(
      ToolRegistry registry,
      AgentType type,
      AgentId id,
      TurnObserver turn,
      Executor executor,
      DeferredToolCallPolicy deferredToolCallPolicy,
      ApprovalContexts approvalContexts,
      ObjectMapper mapper) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
    this.deferredToolCallPolicy =
        Objects.requireNonNull(deferredToolCallPolicy, "deferredToolCallPolicy must not be null");
    this.approvalContexts =
        Objects.requireNonNull(approvalContexts, "approvalContexts must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.codecs = new Codecs(this.mapper);
  }

  @Override
  public void seekApproval(ToolCall call, ModelResponseId responseId, Sink sink) {
    Objects.requireNonNull(responseId, "responseId must not be null");
    executor.execute(
        () -> {
          AgentEvent event = seek(call, responseId, sink);
          if (event != null) {
            sink.deliver(event);
          }
        });
  }

  @Override
  public void runTool(ToolCall call, ModelResponseId responseId, Sink sink) {
    Objects.requireNonNull(responseId, "responseId must not be null");
    executor.execute(
        () -> {
          CallAddress address = address(call, responseId);
          switch (runPastGate(call, address)) {
            case ToolExecution.Immediate(ToolOutcome outcome) ->
                sink.deliver(new AgentEvent.ToolFinished(call, Optional.empty(), outcome));
            case ToolExecution.Deferred(ComputationId deferredId) ->
                sink.deliver(new AgentEvent.ToolDeferred(call, deferredId));
          }
        });
  }

  /** The ask. Returns the event to deliver; a deferral has already delivered its own. */
  private AgentEvent seek(ToolCall call, ModelResponseId responseId, Sink sink) {
    Optional<ToolGrant> found = registry.find(call.name());
    if (found.isEmpty()) {
      return answered(call, Approval.denied("unknown tool: " + call.name()));
    }
    ToolGrant grant = found.get();
    if (grant.approver() instanceof Approvers.Static fixed) {
      return answered(call, fixed.answer()); // rung 0: no request built, no enricher run
    }
    ApprovalRequest request;
    try {
      Object input = convert(call, grant.tool());
      request = grant.request(type.name(), id.value(), call, input, mapper);
    } catch (RuntimeException e) {
      return answered(call, Approval.denied("authorization failed: " + detailOf(e)));
    }
    ApprovalContext context = approvalContexts.contextFor(call, responseId, request, sink);
    ApprovalOutcome outcome;
    try {
      outcome = grant.approver().approve(context);
    } catch (RuntimeException e) {
      return answered(call, Approval.denied("approver failed: " + detailOf(e)));
    }
    return switch (outcome) {
      case ApprovalOutcome.Answered(Approval approval) -> answered(call, approval);
      case ApprovalOutcome.Deferred _ -> null; // defer() delivered ApprovalDeferred itself
    };
  }

  /** Narrates the answer onto the turn and hands back the event the reducer folds. */
  private AgentEvent answered(ToolCall call, Approval approval) {
    turn.on(new TurnEvent.ToolCallDecided(call, approval));
    return new AgentEvent.ApprovalAnswered(call, Optional.empty(), approval);
  }

  private ToolExecution runPastGate(ToolCall call, CallAddress address) {
    Optional<ToolGrant> found = registry.find(call.name());
    if (found.isEmpty()) {
      return new ToolExecution.Immediate(failed(call, "unknown tool: " + call.name()));
    }
    try {
      ToolGrant grant = found.get();
      Object input = convert(call, grant.tool());
      return run(grant.tool(), input, call, address);
    } catch (RuntimeException e) {
      return new ToolExecution.Immediate(failed(call, detailOf(e)));
    }
  }

  private CallAddress address(ToolCall call, ModelResponseId responseId) {
    return new CallAddress(type.name(), id.value(), responseId.value(), call.id());
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

  private <T> ToolExecution run(Tool<T> tool, Object input, ToolCall call, CallAddress address) {
    T typed = tool.inputType().cast(input);
    // address.digest() is deterministic from this call's own coordinates (agentType, agentId,
    // responseId, callId) — stable across every redrive and replay, exactly the contract
    // ToolContext#invocation documents. A genuine Continuum-minted id cannot serve here: it is not
    // known until (and unless) the tool actually defers, since onDeferred only creates a
    // computation on the Awaited.Deferred arm below — after the tool has already been handed this
    // very context.
    ToolContext context =
        new ToolContext(call, event -> narrate(call, event), ComputationId.of(address.digest()));
    return switch (tool.execute(typed, context)) {
      case Awaited.Ready<ToolResult>(ToolResult value) -> {
        turn.on(new TurnEvent.ToolCallCompleted(call, value));
        yield new ToolExecution.Immediate(new ToolOutcome.Returned(value));
      }
      case Awaited.Deferred<ToolResult> _ ->
          deferredToolCallPolicy.onDeferred(call, address, tool.timeout());
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

  private static String detailOf(RuntimeException e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  /** The 5-arg constructor's default: fails loudly in-band rather than suspending silently. */
  private static DeferredToolCallPolicy defaultPolicy(TurnObserver turn) {
    return (call, address, timeout) -> {
      ToolResult error = ToolResult.error(PARKING_UNAVAILABLE);
      turn.on(new TurnEvent.ToolCallCompleted(call, error));
      return new ToolExecution.Immediate(
          new ToolOutcome.Failed(new ToolError(PARKING_UNAVAILABLE)));
    };
  }

  /**
   * The 5- and 6-arg constructors' default: parking is a capability of the wiring, not a right of
   * every deployment, so a wiring with no Continuum behind it cannot park and says so loudly — the
   * {@code approver failed:} catch above turns the throw into a denial the model reads.
   */
  private static ApprovalContexts defaultApprovalContexts() {
    return (call, responseId, request, sink) ->
        new ApprovalContext() {
          @Override
          public ApprovalRequest request() {
            return request;
          }

          @Override
          public ApprovalOutcome defer() {
            throw new IllegalStateException(APPROVAL_UNAVAILABLE);
          }
        };
  }
}
