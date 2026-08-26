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
package org.jwcarman.nessy.agent;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The observability bridge (agentic-o11y spec §1, §3.1): a subscriber on the harness's fact stream
 * that turns folds into Micrometer {@link Observation}s named per the OpenTelemetry GenAI semantic
 * conventions. One per {@link Harness}, holding the configured {@link ObservationRegistry} — {@link
 * ObservationRegistry#NOOP} unless an application supplied one, in which case every method here is
 * inert and free.
 *
 * <p>What it owns: the {@code invoke_agent} SEGMENT, the two {@code nessy.*} WAITS, and the three
 * engine COUNTERS. What it does not: {@code chat} and {@code execute_tool}, which are opened inside
 * {@code ProviderModelCallExecutor} and {@code RegistryToolCallExecutor} because {@code Usage},
 * {@code StopReason} and a tool's deferral exist nowhere else (spec §3.1). Those two read this
 * object's open segment — the parent of every span a scope produces — through the shared {@link
 * #openSegments} map they are handed at construction, because Micrometer's own scope does not
 * follow {@code executor.execute} onto another virtual thread (spec §3.2).
 *
 * <p><b>A segment is not a turn</b> (spec §2). An {@code invoke_agent} span never straddles a park:
 * it runs from {@code Observed} (or whatever delivery resumed the scope) to the next {@code Idle}
 * or the next park — a phase with no call left {@code Running} or {@code Pending}. A turn waiting
 * six hours on a human would otherwise hold an open span across a crash, a restart and a redeploy,
 * and a span that survives none of those is a lie. The dwell between segments is carried by the
 * wait spans instead, which is what a dashboard reads for "how long do humans take". In-flight
 * spans die with the process; nothing reconstructs one after a restart, because they are telemetry
 * and the phase in the store is the truth.
 *
 * <p><b>Why counters are span events</b> (spec §1.2, third amendment — the 2026-08-26 soak, finding
 * F2). An {@link ObservationRegistry} is the only seam this harness has, by ruling — no {@code
 * MeterRegistry} reaches {@code nessy-agent}, ever (which is also why that type is named here in
 * plain code font: it is not on this module's classpath). A registry cannot increment a counter
 * directly, so the three engine counters were originally recorded as zero-duration observations,
 * started and stopped in place. Run against a real collector that turns out to be wrong: a
 * standalone observation is a standalone TRACE, so one healthy round that retried five contended
 * CAS writes produced one round trace and five counter traces, and the trace list stopped being a
 * list of rounds. A counter is a thing that happened DURING a round, so each one is now an {@link
 * Observation.Event} on that scope's open segment — where a reader finds it while looking at the
 * round it belongs to, on a span that already carries {@code gen_ai.agent.name}. Only a scope with
 * no segment open falls back to the old zero-duration shape (see {@link #count}). The consequence,
 * accepted: an event contributes to no timer, so while a round is open these three are readable as
 * span events rather than as meters. The same registry limit is why the semconv {@code
 * gen_ai.client.token.usage} histogram is NOT recorded here (spec §1.2): a registry times
 * observations but cannot record an arbitrary value histogram, so the token counts ride the {@code
 * chat} observation as key-values and the application's own {@code ObservationHandler} reads them
 * on stop and records them to its {@code MeterRegistry}.
 *
 * <p><b>Three operations, three semconv METER names — and they are not the span names</b> (spec
 * §1.2, second amendment, 2026-08-26 semconv audit). Semconv defines a SEPARATE duration histogram
 * per operation boundary, each with its own attribute set: {@code gen_ai.client.operation.duration}
 * for a provider-facing client call, {@code gen_ai.invoke_agent.duration} for "a single in-process
 * agent invocation", and {@code gen_ai.execute_tool.duration} for "a single tool execution". It
 * does NOT mandate one shared name discriminated by {@code gen_ai.operation.name}; {@code
 * gen_ai.invoke_agent.duration}'s own note says {@code gen_ai.client.operation.duration} "SHOULD be
 * used instead" only when instrumentation "can only measure a single provider-facing client
 * operation", which is not this harness.
 *
 * <p>So each observation's Micrometer NAME is its semconv METER name and its {@code contextualName}
 * is its semconv SPAN name ({@code invoke_agent {agent}}, {@code chat {model}}, {@code execute_tool
 * {tool}}). This satisfies Micrometer's own rule for free — a metrics backend requires every
 * observation sharing one name to carry the same set of low-cardinality keys, and three distinct
 * names each keep their own stable set, which is exactly how semconv already partitions the
 * attributes.
 *
 * <p>The same rule is why every outcome-bearing key here is set at START, to {@link
 * KeyValue#NONE_VALUE}, and overwritten when the outcome is known: a context stores its
 * low-cardinality key values by key, so the later write replaces the placeholder, and the key set
 * stays identical across every observation of that name whether or not the outcome ever arrived.
 *
 * <p>Segments and waits are {@link Observation#start()}ed and {@link Observation#stop()}ed
 * explicitly, never through {@code observe(Runnable)}: both span threads by construction — a wait
 * opens on whichever thread folded the park and closes on a delivery worker's thread hours later.
 *
 * <p><b>This is a keyed state machine over a stream with no ordering guarantee</b> (spec §3, fix
 * round 2). Each fold site publishes AFTER its CAS, not under it, so two concurrent folds on one
 * scope can arrive here in either order — a close can precede the open it belongs to. Every
 * transition below is therefore written to tolerate that: closing a wait this object does not hold
 * is a no-op, opening a segment when one is already open is a no-op, and closing one that is
 * already closed is a no-op. The one shape that cannot be detected is an open arriving after its
 * own close; it leaves a span open until the scope's next close, and is accepted (see below).
 *
 * <p><b>Known bound: a wait parked by one harness and answered by another leaks its span.</b> Two
 * harnesses sharing a type, a substrate and a Continuum are a supported shape, and either may
 * deliver what the other parked. The wait's open {@link Observation} lives in the parking harness's
 * heap alone, so an answer folded by the OTHER harness closes nothing: the first harness's span
 * stays open until its process ends, and the second records a close for a wait it never opened (a
 * no-op). Nothing is corrupted and no fold is affected — the dwell simply goes unrecorded, the same
 * way an in-flight span already dies with a restart (spec §2). Accepted rather than fixed: making
 * it work would mean reconstructing spans from durable state, which spec §2 rules out.
 */
final class Observations implements HarnessObserver {

  private static final Logger log = LoggerFactory.getLogger(Observations.class);

  /**
   * The semconv duration histogram for a provider-facing client call — the Micrometer NAME of the
   * {@code chat} observation {@code ProviderModelCallExecutor} mints (which declares its own copy
   * of this string; {@code ObservedTurnTest} pins both ends).
   */
  static final String OPERATION_DURATION = "gen_ai.client.operation.duration";

  /** The semconv duration histogram for one in-process agent invocation — the SEGMENT's name. */
  static final String INVOKE_AGENT_DURATION = "gen_ai.invoke_agent.duration";

  /** The semconv duration histogram for one tool execution — {@code RegistryToolCallExecutor}'s. */
  static final String EXECUTE_TOOL_DURATION = "gen_ai.execute_tool.duration";

  /**
   * The semconv token histogram — named here for the record, never recorded here: see this class's
   * javadoc and spec §1.2. The {@code chat} observation carries the counts as key-values and an
   * application-side handler turns them into this metric.
   */
  static final String TOKEN_USAGE = "gen_ai.client.token.usage";

  /** The {@code gen_ai.operation.name} values, which are also the semconv SPAN name prefixes. */
  static final String INVOKE_AGENT = "invoke_agent";

  static final String CHAT = "chat";
  static final String EXECUTE_TOOL = "execute_tool";

  /**
   * The two memory operations this harness performs, from semconv's own {@code
   * gen_ai.operation.name} enum — NOT invented {@code nessy.memory.*} names (semconv audit A7).
   * {@code search_memory} is "search/query memories from a memory store" ({@code Memory#recall});
   * {@code create_memory} is "create new memory records" ({@code Memory#remember}). Their span name
   * SHOULD be {@code {gen_ai.operation.name}} alone, with no trailing identifier, and semconv
   * defines no duration metric for them — so for these two the observation name IS the span name.
   * Written by {@code ObservingMemory}, which declares its own copies; {@code
   * ObservationsTest.TheRoster} pins these constants and {@code ObservedTurnTest.MemoryOperations}
   * pins what a real turn emits, so a drift between the two breaks the build.
   */
  static final String SEARCH_MEMORY = "search_memory";

  static final String CREATE_MEMORY = "create_memory";

  /**
   * Ours, and confirmed still ours by the 2026-08-26 semconv audit: {@code
   * semantic-conventions-genai} has no convention for a human-in-the-loop pause or a deferred
   * long-running operation — the {@code gen_ai.operation.name} enum runs chat / generate_content /
   * text_completion / embeddings / retrieval / fetch_response / execute_tool / the memory verbs /
   * create_agent / invoke_agent / invoke_workflow / plan, and none of them is a wait.
   */
  static final String APPROVAL_WAIT = "nessy.approval.wait";

  static final String TOOL_WAIT = "nessy.tool.wait";

  /**
   * The fold, as work (in-the-loop amendment §2, §3): load, handle, remember, CAS save. Its
   * duration IS the store write plus the reduce plus the remembrance, which is the question the
   * JDBC library was imported for and the reason that library could retire. Ours, like the waits —
   * semconv has no verb for reducing an event into a phase — and semconv defines no duration metric
   * for it, so the Micrometer name and the span name coincide.
   */
  static final String FOLD = "nessy.fold";

  static final String ERROR_TYPE = "error.type";

  /**
   * Ours, counters (spec §1.2). These are the names of the span EVENTS the three counters record on
   * the open segment, and — for a scope with no segment open — of the fallback observations, which
   * are tagged {@link #GEN_AI_AGENT_NAME} only.
   */
  static final String DELIVERY_DROPPED = "nessy.delivery.dropped";

  static final String STALE_RETRIES = "nessy.state.stale_retries";
  static final String EFFECTS_REFIRED = "nessy.effects.refired";

  static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
  static final String GEN_AI_PROVIDER_NAME = "gen_ai.provider.name";
  static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
  static final String GEN_AI_AGENT_NAME = "gen_ai.agent.name";
  static final String GEN_AI_AGENT_ID = "gen_ai.agent.id";
  static final String GEN_AI_CONVERSATION_ID = "gen_ai.conversation.id";
  static final String GEN_AI_TOOL_NAME = "gen_ai.tool.name";
  static final String GEN_AI_TOOL_CALL_ID = "gen_ai.tool.call.id";
  static final String NESSY_TURN_OUTCOME = "nessy.turn.outcome";
  static final String NESSY_APPROVAL_ANSWER = "nessy.approval.answer";
  static final String NESSY_TOOL_OUTCOME = "nessy.tool.outcome";

  /** The three {@link #NESSY_TURN_OUTCOME} values a segment can close with. */
  static final String COMPLETE = "complete";

  static final String PARKED = "parked";
  static final String FAILED = "failed";

  private final ObservationRegistry registry;
  private final AgentType type;

  /**
   * The provider and model the segment reports (semconv audit A3). {@code gen_ai.request.model} is
   * Recommended on the {@code invoke_agent} span — "the name of the GenAI model configured for the
   * agent" — and is one of the three attributes on {@code gen_ai.invoke_agent.duration}. {@code
   * gen_ai.provider.name} is Required on the invoke_agent CLIENT span (a remote agent) and absent
   * from the INTERNAL one this harness mints; it is carried anyway, honestly, because the harness
   * holds the {@link org.jwcarman.nessy.spi.model.Model} and semconv permits extra attributes.
   */
  private final String provider;

  private final String modelId;

  /**
   * The open {@code invoke_agent} span per scope — shared with the two executors, which parent
   * their own spans off it (spec §3.2). An absent entry means no segment is open for that scope:
   * either nothing is happening, or the last one closed at a park.
   */
  private final ConcurrentMap<AgentId, Observation> openSegments;

  /** The open wait spans per scope, keyed by the call id whose answer closes each one. */
  private final ConcurrentMap<AgentId, ConcurrentMap<String, Observation>> openWaits =
      new ConcurrentHashMap<>();

  Observations(
      ObservationRegistry registry,
      AgentType type,
      String provider,
      String modelId,
      ConcurrentMap<AgentId, Observation> segments) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.modelId = Objects.requireNonNull(modelId, "modelId must not be null");
    this.openSegments = Objects.requireNonNull(segments, "segments must not be null");
  }

  /**
   * One applied fold, as spans. The order matters: the segment is opened FIRST so that a wait this
   * same event opens is parented to it, and the close check runs LAST so a park closes the segment
   * only once the wait that caused it is already open and recorded as its child.
   */
  @Override
  public void applied(AgentId id, AgentEvent event, Transition transition) {
    openSegmentIfAbsent(id);
    switch (event) {
      case AgentEvent.ApprovalDeferred(ToolCall call, var _, var _) ->
          openWait(id, APPROVAL_WAIT, call);
      case AgentEvent.ApprovalAnswered(ToolCall call, var _, Approval answer) ->
          closeWait(id, call, NESSY_APPROVAL_ANSWER, answerOf(answer));
      case AgentEvent.ToolDeferred(ToolCall call, var _) -> openWait(id, TOOL_WAIT, call);
      case AgentEvent.ToolFinished(ToolCall call, var computation, ToolOutcome outcome) -> {
        // Only a DELIVERED result closes a wait: a call the door never deferred ran to completion
        // in-band and never opened one.
        if (computation.isPresent()) {
          closeWait(id, call, NESSY_TOOL_OUTCOME, outcomeOf(outcome));
        }
      }
      case AgentEvent.Observed _, AgentEvent.ModelFinished _ -> {
        // Neither opens nor closes a wait; both are ordinary segment traffic.
      }
    }
    closeSegmentIfEnded(id, event, transition);
  }

  /**
   * A dropped delivery, counted (spec §1.2) — but only a genuine DELIVERY. An ignored event
   * carrying no computation id is an ordinary in-band stale or duplicate fold, which the shell
   * absorbs by design and which is not the operational event {@code nessy.delivery.dropped} exists
   * to surface.
   */
  @Override
  public void ignored(AgentId id, AgentEvent event) {
    if (wasDelivered(event)) {
      dropped(id, type);
    }
  }

  @Override
  public void renderFailed(AgentId id, Object observation, RuntimeException error) {
    // A render failure never reached the reducer, so no span opened, closed, or changed state.
  }

  @Override
  public void applyFailed(AgentId id, AgentEvent event, RuntimeException error) {
    // The fold threw and nothing was written: the phase is unchanged, so the segment stays exactly
    // as it was. The failure is the narrator's story, not a span transition.
  }

  @Override
  public void reFired(AgentId id, List<Effect> effects) {
    refired(id, type, effects.size());
  }

  @Override
  public void observationRequeued(AgentId id, Object observation) {
    // An observation that lost the idle race is retried from the backlog; nothing folded.
  }

  /**
   * {@code nessy.delivery.dropped} (spec §1.2), as a span event on the scope's open segment — see
   * the class javadoc for why a counter is spelled this way.
   */
  void dropped(AgentId id, AgentType agentType) {
    count(id, DELIVERY_DROPPED, agentType);
  }

  /**
   * {@code nessy.state.stale_retries}: one per {@code StaleStateException}/{@code
   * ConflictException} retry, reported directly by the two fold sites — a lost CAS race is an
   * engine-health moment, not a fold, so it never reaches the fact stream.
   *
   * @implSpec Never throws — see {@link #count}. The two fold sites call this from inside their
   *     retry loops, where an escaping exception would abort the very convergence the loop exists
   *     for.
   */
  void staleRetry(AgentId id, AgentType agentType) {
    count(id, STALE_RETRIES, agentType);
  }

  /** {@code nessy.effects.refired}: one per effect the recovery arm re-dispatched (spec §6.1). */
  void refired(AgentId id, AgentType agentType, int effects) {
    for (int i = 0; i < effects; i++) {
      count(id, EFFECTS_REFIRED, agentType);
    }
  }

  /**
   * One fold attempt, measured and made CURRENT (in-the-loop amendment §2). The scope is the whole
   * point: a store that records its own observation — a wrapped {@code DataSource}, a document
   * store's own instrumentation — lands beneath this span instead of starting a trace of its own,
   * which is exactly what the 2026-08-26 soak found it could not do while the bridge was a mere
   * subscriber on the fact stream.
   *
   * <p>What is INSIDE: load, handle, remember, CAS save — {@code attempt} itself. What is
   * deliberately OUTSIDE, at both fold sites: publishing the fold's output on the fact stream, and
   * dispatching the transition's effects. The stream is where the segment opens, and a segment
   * created inside this scope would become the CHILD of a fold that stops immediately — inverting
   * §2's rule that the segment is the parent of everything. So each fold site closes this span
   * first, then publishes.
   *
   * <p>Honest consequence: the FIRST fold of a segment has no segment to hang off, because the
   * segment does not exist until that fold's own output is published. That one span is a root.
   * Every later fold in the round is a child of the segment, and the whole round remains one trace.
   *
   * <p>A CAS conflict propagates out of {@code attempt}, so a retried fold is a SECOND span
   * carrying {@code error.type}, never one long span that hides the contention.
   *
   * @implSpec Never breaks a fold. Every call that can reach an application's {@code
   *     ObservationHandler} — the start, the scope open, the scope close, the stop — is guarded and
   *     logged; only the exception {@code attempt} itself throws ever escapes.
   */
  <T> T fold(AgentId id, AgentType agentType, Supplier<T> attempt) {
    Observation span = startFold(id, agentType);
    Observation.Scope scope = opened(span);
    try {
      return attempt.get();
    } catch (RuntimeException e) {
      quietly(() -> span.lowCardinalityKeyValue(ERROR_TYPE, e.getClass().getSimpleName()));
      quietly(() -> span.error(e));
      throw e;
    } finally {
      quietly(scope::close);
      quietly(span::stop);
    }
  }

  private Observation startFold(AgentId id, AgentType agentType) {
    try {
      Observation span =
          Observation.createNotStarted(FOLD, registry)
              .contextualName(FOLD)
              .lowCardinalityKeyValue(GEN_AI_AGENT_NAME, agentType.name())
              // Declared now, overwritten when known: one stable low-cardinality key set per name.
              .lowCardinalityKeyValue(ERROR_TYPE, KeyValue.NONE_VALUE)
              .highCardinalityKeyValue(GEN_AI_AGENT_ID, id.value());
      Observation parent = foldParent(id);
      if (parent != null) {
        span.parentObservation(parent);
      }
      return span.start();
    } catch (RuntimeException e) {
      log.warn("an observation handler threw starting a fold span; the fold is unaffected", e);
      return Observation.NOOP;
    }
  }

  /**
   * Who a fold span hangs off: an ENCLOSING observation when there is one — a {@code defer()}
   * inside an approver folds while {@code nessy.approval.seek} is current, and the nearest open
   * scope is the truer parent — otherwise the scope's open segment, and otherwise nothing at all
   * (see {@link #fold}'s note on the first fold of a segment).
   */
  private Observation foldParent(AgentId id) {
    Observation enclosing = registry.getCurrentObservation();
    return enclosing != null ? enclosing : openSegments.get(id);
  }

  private Observation.Scope opened(Observation span) {
    try {
      return span.openScope();
    } catch (RuntimeException e) {
      log.warn("an observation handler threw opening a fold scope; the fold is unaffected", e);
      return Observation.Scope.NOOP;
    }
  }

  private static void quietly(Runnable instrumentation) {
    try {
      instrumentation.run();
    } catch (RuntimeException e) {
      log.warn("an observation handler threw around a fold span; the fold is unaffected", e);
    }
  }

  /**
   * The scope's open {@code invoke_agent} span, or {@link Observation#NOOP} when none is open —
   * this class's own reader, used to parent the wait spans it opens. Never null.
   *
   * <p>The two executor-minted spans do NOT come through here: {@code chat} and {@code
   * execute_tool} are parented from a {@code Supplier<Observation>} over the shared {@link
   * #openSegments} map, handed to each executor at construction, because neither executor can name
   * this package-private class across the package line (spec §3.1, §3.2).
   */
  Observation openSegment(AgentId id) {
    Observation segment = openSegments.get(id);
    return segment != null ? segment : Observation.NOOP;
  }

  /**
   * Opens a segment when the scope has none (spec §2): {@code Observed} applied at {@code Idle}
   * starts one, and so does ANY event applied while none is open — a delivery resuming a scope that
   * parked hours ago is the start of a new segment, not the continuation of a dead one.
   */
  private void openSegmentIfAbsent(AgentId id) {
    openSegments.computeIfAbsent(
        id,
        scope ->
            Observation.createNotStarted(INVOKE_AGENT_DURATION, registry)
                .contextualName(INVOKE_AGENT + " " + type.name())
                .lowCardinalityKeyValue(GEN_AI_OPERATION_NAME, INVOKE_AGENT)
                .lowCardinalityKeyValue(GEN_AI_AGENT_NAME, type.name())
                .lowCardinalityKeyValue(GEN_AI_PROVIDER_NAME, provider)
                .lowCardinalityKeyValue(GEN_AI_REQUEST_MODEL, modelId)
                .lowCardinalityKeyValue(NESSY_TURN_OUTCOME, KeyValue.NONE_VALUE)
                .highCardinalityKeyValue(GEN_AI_AGENT_ID, scope.value())
                .highCardinalityKeyValue(GEN_AI_CONVERSATION_ID, scope.value())
                .start());
  }

  /**
   * The two ways a segment ends (spec §2): the scope went {@link Phase.Idle}, or it parked — a
   * phase where nothing is left {@code Running} or {@code Pending}, so no further work happens
   * until something outside answers. A turn that failed is still a turn that ended; {@code
   * ModelFinished(Failed)} folds to {@code Idle} and is what tells the two apart.
   */
  private void closeSegmentIfEnded(AgentId id, AgentEvent event, Transition transition) {
    Phase next = transition.next();
    if (next instanceof Phase.Idle) {
      closeSegment(id, isFailure(event) ? FAILED : COMPLETE);
    } else if (isParked(next)) {
      closeSegment(id, PARKED);
    }
  }

  private static boolean isFailure(AgentEvent event) {
    return event instanceof AgentEvent.ModelFinished(ModelOutcome.Failed _);
  }

  /**
   * A parked phase: {@link Phase.AwaitingTools} with every call either waiting on something outside
   * the process ({@code AwaitingApproval}/{@code AwaitingResult}) or already {@code Finished}.
   * While ANY call is still {@code Running} or {@code Pending} the segment has work in flight and
   * stays open, which is why a park is detected here rather than at the {@code *Deferred} event
   * itself: a call can park while a sibling is still running, and the segment ends only when the
   * last sibling finishes.
   */
  private static boolean isParked(Phase next) {
    if (!(next instanceof Phase.AwaitingTools awaiting)) {
      return false;
    }
    return awaiting.calls().values().stream().noneMatch(Observations::isInFlight);
  }

  private static boolean isInFlight(CallStatus status) {
    return switch (status) {
      case CallStatus.Pending _, CallStatus.Running _ -> true;
      case CallStatus.AwaitingApproval _, CallStatus.AwaitingResult _, CallStatus.Finished _ ->
          false;
    };
  }

  private void closeSegment(AgentId id, String outcome) {
    Observation segment = openSegments.remove(id);
    if (segment != null) {
      segment.lowCardinalityKeyValue(NESSY_TURN_OUTCOME, outcome);
      segment.stop();
    }
  }

  /**
   * Opens a dwell span for {@code call}, parented to the segment that parked it, and keyed by the
   * call id whose answer will close it. A second park for a call that already has an open wait
   * leaves the first one alone rather than orphaning it.
   */
  private void openWait(AgentId id, String name, ToolCall call) {
    Observation parent = openSegment(id);
    // The insert happens INSIDE compute on the scope's own key (fix round 2), not on a map fetched
    // first and written second: acquiring the inner map and then inserting into it can lose the
    // whole wait to a concurrent closeWait that detaches an emptied map in between. Both sides now
    // serialise on the same outer key, so a wait is either in the map the closer sees or in one
    // that is still attached.
    openWaits.compute(
        id,
        (scope, existing) -> {
          ConcurrentMap<String, Observation> waits =
              existing != null ? existing : new ConcurrentHashMap<>();
          waits.computeIfAbsent(
              call.id(),
              callId ->
                  Observation.createNotStarted(name, registry)
                      .parentObservation(parent)
                      .contextualName(name + " " + call.name())
                      .lowCardinalityKeyValue(GEN_AI_AGENT_NAME, type.name())
                      .lowCardinalityKeyValue(GEN_AI_TOOL_NAME, call.name())
                      .lowCardinalityKeyValue(outcomeKeyOf(name), KeyValue.NONE_VALUE)
                      .highCardinalityKeyValue(GEN_AI_TOOL_CALL_ID, callId)
                      .start());
          return waits;
        });
  }

  /**
   * Which outcome key a wait of this name will close with — declared at start as a placeholder so
   * the two wait names each keep one stable low-cardinality key set.
   */
  private static String outcomeKeyOf(String waitName) {
    return APPROVAL_WAIT.equals(waitName) ? NESSY_APPROVAL_ANSWER : NESSY_TOOL_OUTCOME;
  }

  /** Closes {@code call}'s wait, if it has one — a no-op for a call that never parked. */
  private void closeWait(AgentId id, ToolCall call, String key, String value) {
    ConcurrentMap<String, Observation> waits = openWaits.get(id);
    if (waits == null) {
      return;
    }
    Observation wait = waits.remove(call.id());
    if (wait != null) {
      wait.lowCardinalityKeyValue(key, value);
      wait.stop();
    }
    // Only an unclosed wait may keep the scope's entry alive: an emptied map is removed so a
    // long-lived harness does not accumulate one entry per scope it has ever seen. Emptiness is
    // tested INSIDE computeIfPresent (fix round 1) so a concurrent openWait cannot slip a fresh
    // wait into the map between the test and the removal and have it silently dropped.
    openWaits.computeIfPresent(id, (scope, current) -> current.isEmpty() ? null : current);
  }

  private static String answerOf(Approval answer) {
    return switch (answer) {
      case Approval.Approved _ -> "approved";
      case Approval.Denied _ -> "denied";
    };
  }

  private static String outcomeOf(ToolOutcome outcome) {
    return switch (outcome) {
      case ToolOutcome.Returned _ -> "returned";
      case ToolOutcome.Failed _ -> "failed";
    };
  }

  /** Whether an ignored event arrived as a delivery — the only kind {@code dropped} counts. */
  private static boolean wasDelivered(AgentEvent event) {
    return switch (event) {
      case AgentEvent.ApprovalAnswered(var _, var approval, var _) -> approval.isPresent();
      case AgentEvent.ToolFinished(var _, var tool, var _) -> tool.isPresent();
      case AgentEvent.Observed _,
          AgentEvent.ModelFinished _,
          AgentEvent.ApprovalDeferred _,
          AgentEvent.ToolDeferred _ ->
          false;
    };
  }

  /**
   * A counter, spelled as a span EVENT on the scope's open segment — see the class javadoc.
   *
   * <p>The fallback, when the scope has NO segment open, is the old shape: a zero-duration
   * observation carrying the agent type. It is kept rather than dropped because that case is
   * exactly the one worth its own trace — something was counted while no round was running, an
   * orphan delivery or a refire into a scope that is idle — and because an {@link
   * ObservationRegistry} has no other way to register a count at all. It is rare, so it does not
   * reproduce the noise that named this fix.
   *
   * <p>Contained (fix round 1): a throwing {@code ObservationHandler} is logged and dropped, never
   * propagated — an {@code onEvent} that throws exactly as much as a {@code start} that throws. The
   * segment and wait spans above need no such guard of their own — every one of them is reached
   * through {@link HarnessObserver}, and {@link FactFanout} already isolates each subscriber — but
   * the three counters are called STRAIGHT from the two fold sites, which are not behind that
   * isolation. This is what lets {@link #dropped}, {@link #staleRetry} and {@link #refired} promise
   * their callers that they never throw.
   */
  private void count(AgentId id, String name, AgentType agentType) {
    try {
      Observation segment = openSegments.get(id);
      if (segment == null) {
        Observation.createNotStarted(name, registry)
            .lowCardinalityKeyValue(GEN_AI_AGENT_NAME, agentType.name())
            .start()
            .stop();
      } else {
        segment.event(Observation.Event.of(name));
      }
    } catch (RuntimeException e) {
      log.warn("an observation handler threw recording the {} counter; ignored", name, e);
    }
  }
}
