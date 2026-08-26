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
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.ApprovalRouting;
import org.jwcarman.nessy.agent.ComputationApprovalContext;
import org.jwcarman.nessy.agent.ComputationToolContext;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.Routing;
import org.jwcarman.nessy.agent.ToolError;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.codec.Codecs;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * is already a fact in the phase. It creates no computation either (tool-context-defer spec §0): a
 * tool that means to wait says so through {@link ToolContext#defer()}, which creates, folds and
 * commits before it hands the id back. All this door does afterwards is police {@link Awaited}'s
 * two arms against what the door recorded (spec §1.2).
 */
public final class RegistryToolCallExecutor implements ToolCallExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(RegistryToolCallExecutor.class);

  private final ToolRegistry registry;
  private final AgentType type;
  private final AgentId id;
  private final TurnObserver turn;
  private final Executor executor;
  private final ContinuumClient<Approval, ApprovalRouting> approvalClient;
  private final ContinuumClient<ToolResult, Routing> toolClient;
  private final Codecs codecs;
  private final ObjectMapper mapper;

  /** A tool returned {@code Awaited.ready(x)} after its own {@code defer()} recorded the wait. */
  static final String ANSWERED_AFTER_DEFERRING = "tool answered after deferring";

  /** A tool returned {@code Awaited.deferred()} without ever calling {@code context.defer()}. */
  static final String DEFERRED_WITHOUT_DEFER = "deferring tool never called context.defer()";

  /**
   * @param registry the grants this executor serves
   * @param type the recipe's name
   * @param id the scope
   * @param turn where a call's narration goes
   * @param executor where each dispatch runs
   * @param approvalClient the approval kind's Continuum client — required, never null (spec §1.4)
   * @param toolClient the tool kind's Continuum client — required, never null (spec §1.4)
   * @param mapper the harness's pinned mapper
   */
  public RegistryToolCallExecutor(
      ToolRegistry registry,
      AgentType type,
      AgentId id,
      TurnObserver turn,
      Executor executor,
      ContinuumClient<Approval, ApprovalRouting> approvalClient,
      ContinuumClient<ToolResult, Routing> toolClient,
      ObjectMapper mapper) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
    this.approvalClient = Objects.requireNonNull(approvalClient, "approvalClient must not be null");
    this.toolClient = Objects.requireNonNull(toolClient, "toolClient must not be null");
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
        () ->
            runPastGate(call, responseId, sink)
                .ifPresent(
                    outcome ->
                        sink.deliver(
                            new AgentEvent.ToolFinished(call, Optional.empty(), outcome))));
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
      LOG.warn(
          "authorization failed building the request for call {} on tool {}; denying",
          call.id(),
          call.name(),
          e);
      return answered(call, Approval.denied("authorization failed: " + detailOf(e)));
    }
    ApprovalContext context =
        new ComputationApprovalContext(approvalClient, routing(call, responseId), request, sink);
    ApprovalOutcome outcome;
    try {
      outcome = grant.approver().approve(context);
    } catch (RuntimeException e) {
      LOG.warn(
          "the approver failed answering call {} on tool {}; denying", call.id(), call.name(), e);
      return answered(call, Approval.denied("approver failed: " + detailOf(e)));
    }
    return switch (outcome) {
      case ApprovalOutcome.Answered(Approval approval) -> answered(call, approval);
      case ApprovalOutcome.Deferred _ -> null; // defer() delivered ApprovalDeferred itself
    };
  }

  /**
   * Narrates the answer onto the turn and hands back the event the reducer folds. A denial narrates
   * BOTH the decision and a completion: the reducer turns it into the error result the model reads,
   * so the call is finished, and a finished call is a completion whatever finished it. An approval
   * narrates only the decision — that call has not run yet.
   */
  private AgentEvent answered(ToolCall call, Approval approval) {
    turn.on(new TurnEvent.ToolCallDecided(call, approval));
    if (approval instanceof Approval.Denied(String reason, var _)) {
      turn.on(new TurnEvent.ToolCallCompleted(call, ToolResult.error(reason)));
    }
    return new AgentEvent.ApprovalAnswered(call, Optional.empty(), approval);
  }

  /** Empty means the door already recorded the wait; there is nothing left to deliver. */
  private Optional<ToolOutcome> runPastGate(ToolCall call, ModelResponseId responseId, Sink sink) {
    Optional<ToolGrant> found = registry.find(call.name());
    if (found.isEmpty()) {
      return Optional.of(failed(call, "unknown tool: " + call.name()));
    }
    try {
      ToolGrant grant = found.get();
      Object input = convert(call, grant.tool());
      return run(grant.tool(), input, call, responseId, sink);
    } catch (RuntimeException e) {
      // Including a throw propagated out of context.defer(): nothing was parked, so this call is
      // answered in-band with the failure and nothing dangles (spec §3).
      return Optional.of(failed(call, detailOf(e)));
    }
  }

  private Routing routing(ToolCall call, ModelResponseId responseId) {
    return new Routing(type.name(), id.value(), responseId.value(), call);
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

  private <T> Optional<ToolOutcome> run(
      Tool<T> tool, Object input, ToolCall call, ModelResponseId responseId, Sink sink) {
    T typed = tool.inputType().cast(input);
    ComputationToolContext context =
        new ComputationToolContext(
            toolClient,
            routing(call, responseId),
            tool.timeout(),
            event -> narrate(call, event),
            sink);
    Awaited<ToolResult> outcome = tool.execute(typed, context);
    boolean deferred = context.deferral().isPresent();
    return switch (outcome) {
      case Awaited.Ready<ToolResult>(ToolResult value) when !deferred -> {
        turn.on(new TurnEvent.ToolCallCompleted(call, value));
        yield Optional.of(new ToolOutcome.Returned(value));
      }
      case Awaited.Ready<ToolResult> _ -> Optional.of(failed(call, ANSWERED_AFTER_DEFERRING));
      case Awaited.Deferred<ToolResult> _ when deferred -> Optional.empty();
      case Awaited.Deferred<ToolResult> _ -> Optional.of(failed(call, DEFERRED_WITHOUT_DEFER));
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
}
