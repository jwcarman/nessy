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
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
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
 * <p>The {@code execute_tool} span (agentic-o11y spec §1.1, §3.1) is opened here rather than
 * derived from the fact stream, because for a DEFERRING tool the execution ends when the body
 * returns, not when {@code ToolFinished} folds — and only this door knows when that was. It covers
 * conversion and {@code tool.execute} together, carries {@code nessy.tool.deferred} from {@code
 * context.deferral()}, and is parented explicitly to the scope's open segment, since Micrometer's
 * scope does not follow {@code executor.execute} onto another virtual thread (spec §3.2). The dwell
 * that follows a deferral is a different span entirely: {@code nessy.tool.wait}, opened by the
 * fold.
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
  private final ObservationRegistry observations;
  private final Supplier<Observation> parentSegment;

  /**
   * The semconv names this executor's own span carries (agentic-o11y spec §1.1, corrected by the
   * 2026-08-26 semconv audit). Semconv defines {@code gen_ai.execute_tool.duration} — "the duration
   * of a single tool execution" — as this operation's own histogram, with its own attribute set
   * ({@code gen_ai.tool.name} Required, {@code error.type}, {@code gen_ai.agent.name}, {@code
   * gen_ai.tool.type}); it is the observation's Micrometer NAME, and {@code execute_tool {tool}} is
   * the semconv SPAN name, carried as the contextual name.
   */
  private static final String EXECUTE_TOOL_DURATION = "gen_ai.execute_tool.duration";

  private static final String EXECUTE_TOOL = "execute_tool";

  private static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
  private static final String GEN_AI_AGENT_NAME = "gen_ai.agent.name";
  private static final String GEN_AI_TOOL_NAME = "gen_ai.tool.name";
  private static final String GEN_AI_TOOL_CALL_ID = "gen_ai.tool.call.id";
  private static final String GEN_AI_TOOL_TYPE = "gen_ai.tool.type";
  private static final String FUNCTION = "function";
  private static final String NESSY_TOOL_DEFERRED = "nessy.tool.deferred";

  /**
   * What the tool's BODY did (in-the-loop amendment §2), beside the boolean above. The boolean
   * answers "is a wait coming"; this answers "what happened", and the two diverge on the failure
   * paths — a tool that throws AFTER a successful {@code defer()} is {@code deferred=true} and
   * {@code outcome=failed} at once. Deliberately the same three words {@code nessy.tool.wait}
   * closes with, so one filter reads the execution and the dwell it opened.
   */
  private static final String NESSY_TOOL_OUTCOME = "nessy.tool.outcome";

  private static final String RETURNED = "returned";
  private static final String FAILED = "failed";
  private static final String ERROR_TYPE = "error.type";

  /**
   * The ask's own span (in-the-loop amendment §2). Ours, not semconv's: the 2026-08-26 registry
   * audit found no {@code gen_ai} attribute for approval, permission, consent, elicitation,
   * confirmation, review or escalation, and no human verb among {@code gen_ai.operation.name}'s
   * eighteen values — so this is named the way the two waits are, and its outcome vocabulary is
   * deliberately the same one {@code nessy.approval.answer} uses, so one filter spans the decision
   * and the wait it opened. Semconv defines no duration metric for it either, so for this span the
   * Micrometer name and the span-name prefix coincide.
   */
  private static final String APPROVAL_SEEK = "nessy.approval.seek";

  private static final String NESSY_APPROVAL_OUTCOME = "nessy.approval.outcome";

  /** The three {@link #NESSY_APPROVAL_OUTCOME} values, mapped from the sealed grammar below. */
  private static final String APPROVED = "approved";

  private static final String DENIED = "denied";
  private static final String DEFERRED = "deferred";

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
   * @param observations where the {@code execute_tool} span is recorded — {@code
   *     ObservationRegistry.NOOP} unless the application supplied one
   * @param parentSegment this scope's open {@code invoke_agent} observation, or null when none is
   *     open
   */
  public RegistryToolCallExecutor(
      ToolRegistry registry,
      AgentType type,
      AgentId id,
      TurnObserver turn,
      Executor executor,
      ContinuumClient<Approval, ApprovalRouting> approvalClient,
      ContinuumClient<ToolResult, Routing> toolClient,
      ObjectMapper mapper,
      ObservationRegistry observations,
      Supplier<Observation> parentSegment) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
    this.approvalClient = Objects.requireNonNull(approvalClient, "approvalClient must not be null");
    this.toolClient = Objects.requireNonNull(toolClient, "toolClient must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.codecs = new Codecs(this.mapper);
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
    this.parentSegment = Objects.requireNonNull(parentSegment, "parentSegment must not be null");
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
                    answer -> {
                      sink.deliver(
                          new AgentEvent.ToolFinished(call, answer.riding(), answer.outcome()));
                      // The call is finished now, so end the computation nobody is waiting on. A
                      // no-op unless this dispatch actually deferred.
                      answer.door().ifPresent(door -> door.abandon(reasonOf(answer.outcome())));
                    }));
  }

  private static String reasonOf(ToolOutcome outcome) {
    return switch (outcome) {
      case ToolOutcome.Failed(ToolError error) -> error.message();
      case ToolOutcome.Returned(ToolResult result) -> result.content();
    };
  }

  /**
   * One dispatch's answer, and the door it ran behind — empty only when there was no tool to run at
   * all.
   *
   * @param outcome what to tell the reducer
   * @param door this dispatch's context, which knows whether it ever deferred
   */
  private record Answer(ToolOutcome outcome, Optional<ComputationToolContext> door) {

    /**
     * The computation this answer must ride to be admitted.
     *
     * <p>Empty for a call the door never deferred — a {@code Running} call names no computation,
     * and the reducer admits only an id-less result against one. <b>Present</b> whenever {@code
     * defer()} succeeded and the dispatch nevertheless ended in a failure: the phase already says
     * {@code AwaitingResult(id)}, so an id-less {@code ToolFinished} would be ignored and the call
     * would hang. Riding the id the phase names is what lets the reducer fold {@code Finished} now.
     */
    Optional<ComputationId> riding() {
      return door.flatMap(ComputationToolContext::deferral);
    }
  }

  /**
   * The ask, measured. Returns the event to deliver; a deferral has already delivered its own.
   *
   * <p>The {@code nessy.approval.seek} span (in-the-loop amendment §2) covers everything the ask
   * does — building the frozen request, the grant's action contributor, every enricher, and the
   * approver call itself — and holds a SCOPE while it does, because the whole premise of the
   * approval design is that an approver may call Slack, a policy service or a rules ladder, and
   * anything it touches should nest inside the ask rather than start a trace of its own. It also
   * records WHAT was decided: {@code nessy.approval.outcome}, in the same vocabulary {@code
   * nessy.approval.answer} uses on the wait this ask may open.
   */
  private AgentEvent seek(ToolCall call, ModelResponseId responseId, Sink sink) {
    Observation ask = startSeek(call);
    Observation.Scope scope = opened(ask);
    try {
      ApprovalOutcome outcome = decide(call, responseId, sink, ask);
      ask.lowCardinalityKeyValue(NESSY_APPROVAL_OUTCOME, outcomeOf(outcome));
      return switch (outcome) {
        case ApprovalOutcome.Answered(Approval approval) -> answered(call, approval);
        case ApprovalOutcome.Deferred _ -> null; // defer() delivered ApprovalDeferred itself
      };
    } finally {
      quietly(scope::close);
      quietly(ask::stop);
    }
  }

  /**
   * The ask's decision, unchanged in behaviour from before the span existed: a fail-closed denial
   * for an unknown tool, for a request that would not build, and for an approver that threw. The
   * two failure arms additionally stamp {@code error.type} on {@code ask}, so a denial the executor
   * manufactured is distinguishable from one an approver meant.
   */
  private ApprovalOutcome decide(
      ToolCall call, ModelResponseId responseId, Sink sink, Observation ask) {
    Optional<ToolGrant> found = registry.find(call.name());
    if (found.isEmpty()) {
      return new ApprovalOutcome.Answered(Approval.denied("unknown tool: " + call.name()));
    }
    ToolGrant grant = found.get();
    if (grant.approver() instanceof Approvers.Static fixed) {
      // rung 0: no request built, no enricher run
      return new ApprovalOutcome.Answered(fixed.answer());
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
      ask.lowCardinalityKeyValue(ERROR_TYPE, e.getClass().getSimpleName());
      return new ApprovalOutcome.Answered(Approval.denied("authorization failed: " + detailOf(e)));
    }
    ApprovalContext context =
        new ComputationApprovalContext(approvalClient, routing(call, responseId), request, sink);
    try {
      return grant.approver().approve(context);
    } catch (RuntimeException e) {
      LOG.warn(
          "the approver failed answering call {} on tool {}; denying", call.id(), call.name(), e);
      ask.lowCardinalityKeyValue(ERROR_TYPE, e.getClass().getSimpleName());
      return new ApprovalOutcome.Answered(Approval.denied("approver failed: " + detailOf(e)));
    }
  }

  /**
   * The outcome as the span records it, mapped from the sealed {@link ApprovalOutcome}/{@link
   * Approval} grammar with NO default arm — a new variant of either fails this build here rather
   * than quietly reading as one of the three words below.
   */
  private static String outcomeOf(ApprovalOutcome outcome) {
    return switch (outcome) {
      case ApprovalOutcome.Answered(Approval approval) ->
          switch (approval) {
            case Approval.Approved _ -> APPROVED;
            case Approval.Denied _ -> DENIED;
          };
      case ApprovalOutcome.Deferred _ -> DEFERRED;
    };
  }

  /**
   * What the body did, as the span records it. An EMPTY answer is the one shape that means the door
   * recorded a wait and there is nothing left to deliver — that is a deferral. Otherwise the sealed
   * {@link ToolOutcome} grammar decides, with no default arm, so a new variant fails this build.
   */
  private static String toolOutcomeOf(Optional<Answer> answer) {
    return answer
        .map(
            found ->
                switch (found.outcome()) {
                  case ToolOutcome.Returned _ -> RETURNED;
                  case ToolOutcome.Failed _ -> FAILED;
                })
        .orElse(DEFERRED);
  }

  /** The ask's span, parented like every other this executor mints. */
  private Observation startSeek(ToolCall call) {
    return started(() -> newSeek(call));
  }

  private Observation newSeek(ToolCall call) {
    Observation parent = parentOf();
    Observation ask =
        Observation.createNotStarted(APPROVAL_SEEK, observations)
            .contextualName(APPROVAL_SEEK + " " + call.name())
            .lowCardinalityKeyValue(GEN_AI_TOOL_NAME, call.name())
            // Carried for the same reason the wait spans carry it: one Grafana filter should span
            // the decision and the dwell it opened, and both are read per agent.
            .lowCardinalityKeyValue(GEN_AI_AGENT_NAME, type.name())
            // Declared now, overwritten when known: one stable low-cardinality key set per name.
            .lowCardinalityKeyValue(NESSY_APPROVAL_OUTCOME, KeyValue.NONE_VALUE)
            .lowCardinalityKeyValue(ERROR_TYPE, KeyValue.NONE_VALUE)
            .highCardinalityKeyValue(GEN_AI_TOOL_CALL_ID, call.id());
    if (parent != null) {
      ask.parentObservation(parent);
    }
    return ask.start();
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
  private Optional<Answer> runPastGate(ToolCall call, ModelResponseId responseId, Sink sink) {
    Optional<ToolGrant> found = registry.find(call.name());
    if (found.isEmpty()) {
      return Optional.of(
          new Answer(failed(call, "unknown tool: " + call.name()), Optional.empty()));
    }
    ToolGrant grant = found.get();
    // Built before conversion so the catch below can ask whether the door was ever opened. Creating
    // one costs nothing and touches no Continuum: only defer() creates a computation.
    ComputationToolContext context =
        new ComputationToolContext(
            toolClient,
            routing(call, responseId),
            grant.tool().timeout(),
            event -> narrate(call, event),
            sink);
    Observation execution = startExecuteTool(call);
    // The scope is what lets the tool's OWN work nest (in-the-loop amendment §1, §2): an HTTP call,
    // a query, a nested agent invocation inside the body attaches beneath this span instead of
    // becoming a root. Closed in the finally, before the stop.
    Observation.Scope scope = opened(execution);
    try {
      Object input = convert(call, grant.tool());
      Optional<Answer> answer = run(grant.tool(), input, call, context);
      // The execution ended when the body returned, deferral or not — that is exactly what this
      // span measures, and the flag says which of the two happened (spec §1.1).
      execution.lowCardinalityKeyValue(
          NESSY_TOOL_DEFERRED, Boolean.toString(context.deferral().isPresent()));
      execution.lowCardinalityKeyValue(NESSY_TOOL_OUTCOME, toolOutcomeOf(answer));
      return answer;
    } catch (RuntimeException e) {
      // Two shapes land here. A throw propagated OUT of defer() means nothing was parked, and
      // context.deferral() is empty — the call is answered in-band and nothing dangles (spec §3).
      // A throw AFTER a successful defer() leaves the phase at AwaitingResult(id), so the failure
      // rides that id or the call hangs until the orphan expires.
      execution.lowCardinalityKeyValue(ERROR_TYPE, e.getClass().getSimpleName());
      execution.lowCardinalityKeyValue(NESSY_TOOL_OUTCOME, FAILED);
      quietly(() -> execution.error(e));
      return Optional.of(new Answer(failed(call, detailOf(e)), Optional.of(context)));
    } finally {
      quietly(scope::close);
      quietly(execution::stop);
    }
  }

  /**
   * Opens one observation's scope, containing anything it throws — a {@code ScopeOpened} callback
   * is an application's handler like any other. A failed open yields {@link
   * Observation.Scope#NOOP}, so the {@code close()} in the {@code finally} is a harmless no-op
   * rather than a second failure on the same broken handler.
   */
  private static Observation.Scope opened(Observation observation) {
    try {
      return observation.openScope();
    } catch (RuntimeException e) {
      LOG.warn("an observation handler threw opening a scope; the tool call is unaffected", e);
      return Observation.Scope.NOOP;
    }
  }

  /**
   * Runs one instrumentation call, containing anything it throws (fix round 1). A turn must never
   * fail because the thing describing it did: an {@code ObservationHandler} lives in the
   * application, is arbitrary code, and reads key-values that a given span may legitimately not
   * carry — an application handler reading {@code gen_ai.usage.input_tokens} off a {@code chat}
   * that failed before the model reported any usage is the case that named this rule. Telemetry is
   * a description of the work, never a participant in it.
   */
  private static void quietly(Runnable instrumentation) {
    try {
      instrumentation.run();
    } catch (RuntimeException e) {
      LOG.warn("an observation handler threw around a tool span; the tool call is unaffected", e);
    }
  }

  /**
   * Starts one observation, containing anything it throws (fix round 1) — see {@link #quietly}. A
   * failed start yields {@link Observation#NOOP}, so the {@code stop()} and the key-value writes
   * that follow are harmless no-ops rather than a second failure on the same broken handler.
   */
  private static Observation started(Supplier<Observation> start) {
    try {
      return start.get();
    } catch (RuntimeException e) {
      LOG.warn("an observation handler threw starting a tool span; the tool call is unaffected", e);
      return Observation.NOOP;
    }
  }

  /**
   * The {@code execute_tool} span (agentic-o11y spec §1.1), started rather than {@code observe}d
   * because a deferring tool's body returns on this thread while the answer arrives on another one
   * entirely. Parented to the scope's open segment; parentless when the scope has none.
   */
  private Observation startExecuteTool(ToolCall call) {
    return started(() -> newExecuteTool(call));
  }

  /**
   * Who a span this executor mints hangs off. An ENCLOSING observation wins when there is one — the
   * nearest open scope is a truer parent than a hand-looked-up segment (in-the-loop amendment §2).
   * The segment is the fallback for the case Micrometer's own scope cannot reach: each dispatch
   * runs on its own virtual thread, where no scope followed it (agentic-o11y spec §3.2).
   */
  private Observation parentOf() {
    Observation enclosing = observations.getCurrentObservation();
    return enclosing != null ? enclosing : parentSegment.get();
  }

  private Observation newExecuteTool(ToolCall call) {
    Observation parent = parentOf();
    Observation execution =
        Observation.createNotStarted(EXECUTE_TOOL_DURATION, observations)
            .contextualName(EXECUTE_TOOL + " " + call.name())
            .lowCardinalityKeyValue(GEN_AI_OPERATION_NAME, EXECUTE_TOOL)
            .lowCardinalityKeyValue(GEN_AI_TOOL_NAME, call.name())
            // Conditionally Required "when applicable" on both the execute_tool span and
            // gen_ai.execute_tool.duration: the agent executing the tool always has a name here.
            .lowCardinalityKeyValue(GEN_AI_AGENT_NAME, type.name())
            .lowCardinalityKeyValue(GEN_AI_TOOL_TYPE, FUNCTION)
            // Declared now, overwritten when known: one stable low-cardinality key set per name.
            .lowCardinalityKeyValue(NESSY_TOOL_DEFERRED, KeyValue.NONE_VALUE)
            .lowCardinalityKeyValue(NESSY_TOOL_OUTCOME, KeyValue.NONE_VALUE)
            .lowCardinalityKeyValue(ERROR_TYPE, KeyValue.NONE_VALUE)
            .highCardinalityKeyValue(GEN_AI_TOOL_CALL_ID, call.id());
    if (parent != null) {
      execution.parentObservation(parent);
    }
    return execution.start();
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

  private <T> Optional<Answer> run(
      Tool<T> tool, Object input, ToolCall call, ComputationToolContext context) {
    T typed = tool.inputType().cast(input);
    Awaited<ToolResult> outcome = tool.execute(typed, context);
    Optional<ComputationToolContext> door = Optional.of(context);
    boolean deferred = context.deferral().isPresent();
    return switch (outcome) {
      case Awaited.Ready<ToolResult>(ToolResult value) when !deferred -> {
        turn.on(new TurnEvent.ToolCallCompleted(call, value));
        yield Optional.of(new Answer(new ToolOutcome.Returned(value), door));
      }
      // The phase already says AwaitingResult(id): the failure rides that id so the reducer folds
      // Finished now, rather than leaving the call to hang until the orphan expires.
      case Awaited.Ready<ToolResult> _ ->
          Optional.of(new Answer(failed(call, ANSWERED_AFTER_DEFERRING), door));
      case Awaited.Deferred<ToolResult> _ when deferred -> Optional.empty();
      case Awaited.Deferred<ToolResult> _ ->
          Optional.of(new Answer(failed(call, DEFERRED_WITHOUT_DEFER), door));
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
