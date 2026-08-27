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
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.api.Computation;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.ApprovalRouting;
import org.jwcarman.nessy.agent.ContinuumIds;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.Routing;
import org.jwcarman.nessy.agent.ToolCallAddress;
import org.jwcarman.nessy.agent.ToolError;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.codec.Codecs;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ComputationCallback;
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
 * denial too. A deferral is now an ordinary RETURN (deferral-by-callback spec §9a): the outcome
 * becomes {@code ApprovalDeferralRequested}, carrying the callback and the term but no id, because
 * at that moment nothing has been created. The creating is {@link #deferApproval}'s job, dispatched
 * only after that fold has committed.
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
 * is already a fact in the phase — and it creates no computation. A tool that means to wait returns
 * {@link Awaited.Deferred}, and there is nothing left to police: an id it could lie about does not
 * exist yet, so the two in-band failures the old {@code defer()} door made writable are gone along
 * with their guard code (spec §7).
 *
 * <p>{@link #deferApproval} and {@link #deferToolCall} are the handoff doors, and the only place in
 * this executor that touches Continuum. Each creates the computation, clips the term to its side's
 * ceiling, reads the deadline Continuum actually stamped, <b>folds the park</b>, and only then runs
 * the callback — see {@link #handOff} for why that order is the whole point. A callback that throws
 * fails the computation and fails the CALL (spec §9a): all we know is that it threw, not whether it
 * reached the world first, so re-asking would risk telling the world twice.
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
  private final Duration approvalCeiling;
  private final Duration toolCeiling;

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
   * answers "is a wait coming"; this answers "what happened". They no longer diverge — a tool that
   * throws has created nothing to be waiting on — but both are still carried, because the boolean
   * is what a dashboard filters on. Deliberately the same three words {@code nessy.tool.wait}
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
   * @param approvalCeiling the longest an approval may stand, whatever term an approver asks for
   *     (spec §5)
   * @param toolCeiling the longest a deferred tool call may stand, whatever term a tool asks for
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
      Supplier<Observation> parentSegment,
      Duration approvalCeiling,
      Duration toolCeiling) {
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
    this.approvalCeiling = Objects.requireNonNull(approvalCeiling, "approvalCeiling must not null");
    this.toolCeiling = Objects.requireNonNull(toolCeiling, "toolCeiling must not be null");
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
    executor.execute(() -> sink.deliver(runPastGate(call, responseId)));
  }

  @Override
  public void deferApproval(
      ToolCall call,
      ApprovalRequest request,
      ComputationCallback callback,
      Duration term,
      ModelResponseId responseId,
      Sink sink) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(callback, "callback must not be null");
    Objects.requireNonNull(term, "term must not be null");
    Objects.requireNonNull(responseId, "responseId must not be null");
    executor.execute(
        () ->
            handOff(
                call,
                callback,
                () ->
                    approvalClient.create(
                        new ApprovalRouting(routing(call, responseId), request),
                        clipped(term, approvalCeiling)),
                approvalClient,
                (id, deadline) -> new AgentEvent.ApprovalDeferred(call, id, request, deadline),
                sink));
  }

  @Override
  public void deferToolCall(
      ToolCall call,
      ComputationCallback callback,
      Duration term,
      ModelResponseId responseId,
      Sink sink) {
    Objects.requireNonNull(callback, "callback must not be null");
    Objects.requireNonNull(term, "term must not be null");
    Objects.requireNonNull(responseId, "responseId must not be null");
    executor.execute(
        () ->
            handOff(
                call,
                callback,
                () -> toolClient.create(routing(call, responseId), clipped(term, toolCeiling)),
                toolClient,
                (id, deadline) -> new AgentEvent.ToolCallDeferred(call, id, deadline),
                sink));
  }

  /** What a party asked for, or what the harness allows — whichever is shorter (spec §5). */
  private static Duration clipped(Duration term, Duration ceiling) {
    return term.compareTo(ceiling) < 0 ? term : ceiling;
  }

  /**
   * One handoff, both sides (spec §9a, ordering ruled by James 2026-08-26). Three steps, and the
   * ORDER IS THE POINT:
   *
   * <ol>
   *   <li>create the computation, and read the deadline Continuum actually stamped — never {@code
   *       Instant.now()} plus a guess, so what the callback is told is exactly what will expire;
   *   <li><b>fold the park and let it commit</b>, so the phase names the id;
   *   <li>only then run the callback.
   * </ol>
   *
   * <p><b>Why the fold comes first.</b> The callback is the one thing that tells the world where to
   * answer, and the world is free to answer instantly — an external system that completes the
   * computation and drains it on this very thread. If the fold came after, that answer would meet a
   * call still in {@code Deferring…}, which has recorded no id, and would be DROPPED permanently:
   * the call would then park on a computation that had already been completed and acked, and hang
   * forever. Folding first makes the answer land by construction rather than by being slow enough.
   * (The alternative — letting {@code Deferring…} admit any id — trades the hang for a correctness
   * bug, because a stale orphan from an earlier attempt could finish the call with the wrong
   * answer. Never that trade.)
   *
   * <p>The fold is {@code sink.deliver}, which RETHROWS if it could not commit. That is deliberate:
   * a park that did not commit must not be followed by a callback, so the throw propagates and the
   * callback never runs. The computation created a moment earlier is then an orphan that expires at
   * its term — the cost §9a already accounts for, and cheaper than telling the world about a wait
   * the scope does not name.
   *
   * <p>Everything that can go wrong fails the CALL rather than re-asking. If {@code create} threw
   * there is no computation and nothing was told, so the failure is id-less. If the CALLBACK threw
   * we know only that it threw, never whether it reached the world first; re-asking would assume it
   * did not, which risks telling the world twice. Failing hands the decision about what to do next
   * to the model, which is where every other failure in this executor already puts it.
   *
   * @param mint creates the computation and returns it
   * @param client the kind's client, used only to tidy up after a thrown callback
   * @param parked the fact that records the park — folded BEFORE the callback runs
   */
  private void handOff(
      ToolCall call,
      ComputationCallback callback,
      Supplier<Computation> mint,
      ContinuumClient<?, ?> client,
      Parked parked,
      Sink sink) {
    ComputationId id;
    Instant deadline;
    try {
      Computation created = mint.get();
      id = ComputationId.of(created.id().value().toString());
      deadline = created.deadline();
    } catch (RuntimeException e) {
      LOG.warn("could not create the computation for call {}; failing the call", call.id(), e);
      sink.deliver(finishedFailing(call, "deferral failed: " + detailOf(e)));
      return;
    }
    sink.deliver(parked.of(id, deadline)); // the phase names the id before anyone outside can
    try {
      callback.accept(id, deadline);
    } catch (RuntimeException e) {
      LOG.warn(
          "the deferral callback for call {} threw; failing the call and the computation {}",
          call.id(),
          id.value(),
          e);
      failQuietly(client, call, id);
      sink.deliver(finishedFailingRiding(call, id, "deferral handoff failed: " + detailOf(e)));
    }
  }

  /** The fact a successful handoff folds — {@code ApprovalDeferred} or {@code ToolCallDeferred}. */
  @FunctionalInterface
  private interface Parked {
    AgentEvent of(ComputationId id, Instant deadline);
  }

  /**
   * The failure the call reads in-band when there is no computation to name — a {@code create} that
   * threw, or a call that never got as far as one. Rides NO id on purpose: the phase is still
   * {@code Deferring…} (or {@code RunningTool}), which recorded none, so an id-less completion is
   * the only shape it admits.
   */
  private AgentEvent finishedFailing(ToolCall call, String reason) {
    return new AgentEvent.ToolFinished(call, Optional.empty(), failed(call, reason));
  }

  /**
   * The failure a THROWN CALLBACK reads in-band. It rides {@code id}, because by then the park has
   * folded and the phase is {@code Awaiting…}, which admits only the id it recorded (spec §3). The
   * computation has already been failed, so Continuum's own delivery of that failure arrives later
   * at a call this event has already made terminal, and is dropped with the WARN §9a accepts.
   */
  private AgentEvent finishedFailingRiding(ToolCall call, ComputationId id, String reason) {
    return new AgentEvent.ToolFinished(call, Optional.of(id), failed(call, reason));
  }

  /**
   * Ends the computation a thrown callback left behind (spec §9a). Continuum then delivers that
   * failure to a call this executor has ALREADY failed, so it is dropped with a WARN — accepted
   * deliberately: the alternative is the same dropped delivery seven days later, harder to
   * correlate, or a window in which a crash parks the call for its full term on something nobody
   * was told about. <b>A WARN'd drop immediately following a handoff failure is the cleanup, not a
   * fault.</b>
   *
   * <p>Best effort: if Continuum itself is down, failing to tidy up must not mask the in-band
   * failure the model is about to read.
   */
  private static void failQuietly(ContinuumClient<?, ?> client, ToolCall call, ComputationId id) {
    try {
      client.fail(ContinuumIds.continuumId(id.value()), "deferral handoff failed for " + call.id());
    } catch (RuntimeException e) {
      LOG.warn("could not fail the computation {}; it will expire on its own", id.value(), e);
    }
  }

  /**
   * The ask, measured. Returns the event to deliver — always exactly one, deferral or not.
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
      Decision decided = decide(call, ask);
      ask.lowCardinalityKeyValue(NESSY_APPROVAL_OUTCOME, outcomeOf(decided.outcome()));
      return switch (decided.outcome()) {
        case ApprovalOutcome.Answered(Approval approval) -> answered(call, approval);
        // No id yet, and none can exist: the effect this fact produces is what creates one.
        case ApprovalOutcome.Deferred(var callback, var term) ->
            new AgentEvent.ApprovalDeferralRequested(call, decided.request(), callback, term);
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
  private Decision decide(ToolCall call, Observation ask) {
    Optional<ToolGrant> found = registry.find(call.name());
    if (found.isEmpty()) {
      return Decision.answering(Approval.denied("unknown tool: " + call.name()));
    }
    ToolGrant grant = found.get();
    if (grant.approver() instanceof Approvers.Static fixed) {
      // rung 0: no request built, no enricher run
      return Decision.answering(fixed.answer());
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
      return Decision.answering(Approval.denied("authorization failed: " + detailOf(e)));
    }
    try {
      return new Decision(grant.approver().approve(new Question(request)), request);
    } catch (RuntimeException e) {
      LOG.warn(
          "the approver failed answering call {} on tool {}; denying", call.id(), call.name(), e);
      ask.lowCardinalityKeyValue(ERROR_TYPE, e.getClass().getSimpleName());
      return Decision.answering(Approval.denied("approver failed: " + detailOf(e)));
    }
  }

  /**
   * What the ask produced, and the frozen question it was asked about. The request travels with a
   * deferral because the approval computation's continuation is built from it, and re-running the
   * enrichers to get it back would build a different one.
   *
   * <p>{@code request} is null on every arm that answered without ever building one — an unknown
   * tool, a {@link Approvers.Static} approver, a request that would not build — which is exactly
   * the set of arms that can never be a deferral.
   */
  private record Decision(ApprovalOutcome outcome, ApprovalRequest request) {
    static Decision answering(Approval approval) {
      return new Decision(new ApprovalOutcome.Answered(approval), null);
    }
  }

  /** The whole of an {@link ApprovalContext} now (spec §7): the frozen question, and nothing. */
  private record Question(ApprovalRequest request) implements ApprovalContext {}

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
   * What a returned body did, as the span records it. Mapped from the sealed {@link ToolOutcome}
   * grammar with no default arm, so a new variant fails this build; the deferral case never reaches
   * here, because its caller names it before asking.
   */
  private static String outcomeOf(AgentEvent event) {
    if (!(event instanceof AgentEvent.ToolFinished(var _, var _, ToolOutcome outcome))) {
      return KeyValue.NONE_VALUE;
    }
    return switch (outcome) {
      case ToolOutcome.Returned _ -> RETURNED;
      case ToolOutcome.Failed _ -> FAILED;
    };
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

  /** One dispatch, one fact: what the tool returned, or the deferral it asked for. */
  private AgentEvent runPastGate(ToolCall call, ModelResponseId responseId) {
    Optional<ToolGrant> found = registry.find(call.name());
    if (found.isEmpty()) {
      return finishedFailing(call, "unknown tool: " + call.name());
    }
    ToolGrant grant = found.get();
    ToolContext context =
        new ToolContext(call, event -> narrate(call, event), invocation(call, responseId));
    Observation execution = startExecuteTool(call);
    // The scope is what lets the tool's OWN work nest (in-the-loop amendment §1, §2): an HTTP call,
    // a query, a nested agent invocation inside the body attaches beneath this span instead of
    // becoming a root. Closed in the finally, before the stop.
    Observation.Scope scope = opened(execution);
    try {
      Object input = convert(call, grant.tool());
      AgentEvent produced = run(grant.tool(), input, call, context);
      // The execution ended when the body returned, deferral or not — that is exactly what this
      // span measures, and the flag says which of the two happened (spec §1.1). A deferral has
      // still created nothing: the wait it asked for is opened by the effect this fact produces.
      boolean deferred = produced instanceof AgentEvent.ToolCallDeferralRequested;
      execution.lowCardinalityKeyValue(NESSY_TOOL_DEFERRED, Boolean.toString(deferred));
      execution.lowCardinalityKeyValue(
          NESSY_TOOL_OUTCOME, deferred ? DEFERRED : outcomeOf(produced));
      return produced;
    } catch (RuntimeException e) {
      // Nothing dangles: a tool that throws has created no computation, because a tool cannot
      // create one at all any more (spec §7). The call is answered in-band and that is the whole
      // of it.
      execution.lowCardinalityKeyValue(NESSY_TOOL_DEFERRED, Boolean.toString(false));
      execution.lowCardinalityKeyValue(ERROR_TYPE, e.getClass().getSimpleName());
      execution.lowCardinalityKeyValue(NESSY_TOOL_OUTCOME, FAILED);
      quietly(() -> execution.error(e));
      return finishedFailing(call, detailOf(e));
    } finally {
      quietly(scope::close);
      quietly(execution::stop);
    }
  }

  /**
   * This execution's opaque, stable idempotency key — deterministic from the call's coordinates,
   * identical across every redispatch and replay (computation-identity spec §4 addendum). NOT the
   * computation a deferral parks under: that one does not exist while a tool runs.
   */
  private ComputationId invocation(ToolCall call, ModelResponseId responseId) {
    Routing routing = routing(call, responseId);
    return ComputationId.of(
        new ToolCallAddress(
                routing.agentType(), routing.agentId(), routing.responseId(), routing.call().id())
            .digest());
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

  /**
   * The body, and the fact it produced. Two arms, no guards: {@link Awaited}'s own grammar is now
   * the whole truth about what happened, because a tool has no side door to contradict it through.
   */
  private <T> AgentEvent run(Tool<T> tool, Object input, ToolCall call, ToolContext context) {
    T typed = tool.inputType().cast(input);
    return switch (tool.execute(typed, context)) {
      case Awaited.Ready<ToolResult>(ToolResult value) -> {
        turn.on(new TurnEvent.ToolCallCompleted(call, value));
        yield new AgentEvent.ToolFinished(call, Optional.empty(), new ToolOutcome.Returned(value));
      }
      case Awaited.Deferred<ToolResult>(var callback, var term) ->
          new AgentEvent.ToolCallDeferralRequested(call, callback, term);
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
