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
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.Approval;

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
 * <p><b>Why counters are Observations too.</b> An {@link ObservationRegistry} is the only seam this
 * harness has, by ruling — no {@link io.micrometer.core.instrument.MeterRegistry} reaches {@code
 * nessy-agent}, ever. A registry cannot increment a counter directly, so the three engine counters
 * are recorded as zero-duration observations, started and stopped in place: Micrometer's default
 * meter handler times every observation, so each one contributes a count (and a negligible
 * duration) to a timer of that name, which is the count a dashboard reads. The same limit is why
 * the semconv {@code gen_ai.client.token.usage} histogram is NOT recorded here (spec §1.2): a
 * registry times observations but cannot record an arbitrary value histogram, so the token counts
 * ride the {@code chat} observation as key-values and the application's own {@code
 * ObservationHandler} reads them on stop and records them to its {@code MeterRegistry}.
 *
 * <p><b>Why one meter name cannot serve three operations</b> (amending spec §1.2). The spec asked
 * for {@code gen_ai.client.operation.duration} as every duration observation's Micrometer NAME,
 * with the semconv span name carried as the contextual name. Micrometer forbids it: a metrics
 * backend requires every observation sharing a name to carry the SAME set of low-cardinality keys,
 * and {@code invoke_agent}, {@code chat} and {@code execute_tool} carry deliberately different ones
 * — that is a meter with unstable tags, which the TCK rejects outright and a real backend corrupts.
 * So each operation is named for itself ({@code invoke_agent} / {@code chat} / {@code
 * execute_tool}), which is also its semconv SPAN name, and the semconv metric is the application's
 * to derive — the same division of labour spec §1.2 already accepted for the token histogram.
 *
 * <p>The same rule is why every outcome-bearing key here is set at START, to {@link
 * KeyValue#NONE_VALUE}, and overwritten when the outcome is known: a context stores its
 * low-cardinality key values by key, so the later write replaces the placeholder, and the key set
 * stays identical across every observation of that name whether or not the outcome ever arrived.
 *
 * <p>Segments and waits are {@link Observation#start()}ed and {@link Observation#stop()}ed
 * explicitly, never through {@code observe(Runnable)}: both span threads by construction — a wait
 * opens on whichever thread folded the park and closes on a delivery worker's thread hours later.
 */
final class Observations implements HarnessObserver {

  /**
   * The semconv duration histogram — named here for the record, and deliberately NOT used as any
   * observation's Micrometer name (a spec §1.2 amendment; see this class's javadoc, "Why one meter
   * name cannot serve three operations"). An application that wants precisely this metric maps the
   * three operation observations onto it in its own {@code ObservationHandler}.
   */
  static final String OPERATION_DURATION = "gen_ai.client.operation.duration";

  /**
   * The semconv token histogram — named here for the record, never recorded here: see this class's
   * javadoc and spec §1.2. The {@code chat} observation carries the counts as key-values and an
   * application-side handler turns them into this metric.
   */
  static final String TOKEN_USAGE = "gen_ai.client.token.usage";

  static final String INVOKE_AGENT = "invoke_agent";
  static final String CHAT = "chat";
  static final String EXECUTE_TOOL = "execute_tool";
  static final String APPROVAL_WAIT = "nessy.approval.wait";
  static final String TOOL_WAIT = "nessy.tool.wait";

  /** Ours, counters (spec §1.2), tagged {@link #GEN_AI_AGENT_NAME} only. */
  static final String DELIVERY_DROPPED = "nessy.delivery.dropped";

  static final String STALE_RETRIES = "nessy.state.stale_retries";
  static final String EFFECTS_REFIRED = "nessy.effects.refired";

  static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
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
   * The open {@code invoke_agent} span per scope — shared with the two executors, which parent
   * their own spans off it (spec §3.2). An absent entry means no segment is open for that scope:
   * either nothing is happening, or the last one closed at a park.
   */
  private final ConcurrentMap<AgentId, Observation> openSegments;

  /** The open wait spans per scope, keyed by the call id whose answer closes each one. */
  private final ConcurrentMap<AgentId, ConcurrentMap<String, Observation>> openWaits =
      new ConcurrentHashMap<>();

  Observations(
      ObservationRegistry registry, AgentType type, ConcurrentMap<AgentId, Observation> segments) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.type = Objects.requireNonNull(type, "type must not be null");
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
      dropped(type);
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
    refired(type, effects.size());
  }

  @Override
  public void observationRequeued(AgentId id, Object observation) {
    // An observation that lost the idle race is retried from the backlog; nothing folded.
  }

  /**
   * {@code nessy.delivery.dropped} (spec §1.2), as a zero-duration observation — see the class
   * javadoc for why a counter is spelled this way.
   */
  void dropped(AgentType agentType) {
    count(DELIVERY_DROPPED, agentType);
  }

  /**
   * {@code nessy.state.stale_retries}: one per {@code StaleStateException}/{@code
   * ConflictException} retry, reported directly by the two fold sites — a lost CAS race is an
   * engine-health moment, not a fold, so it never reaches the fact stream.
   */
  void staleRetry(AgentType agentType) {
    count(STALE_RETRIES, agentType);
  }

  /** {@code nessy.effects.refired}: one per effect the recovery arm re-dispatched (spec §6.1). */
  void refired(AgentType agentType, int effects) {
    for (int i = 0; i < effects; i++) {
      count(EFFECTS_REFIRED, agentType);
    }
  }

  /**
   * The scope's open {@code invoke_agent} span, or {@link Observation#NOOP} when none is open — the
   * parent the two executor-minted spans hang off (spec §3.2). Never null: a {@code chat} that
   * somehow runs outside any segment is parentless, not broken.
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
            Observation.createNotStarted(INVOKE_AGENT, registry)
                .contextualName(INVOKE_AGENT + " " + type.name())
                .lowCardinalityKeyValue(GEN_AI_OPERATION_NAME, INVOKE_AGENT)
                .lowCardinalityKeyValue(GEN_AI_AGENT_NAME, type.name())
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
    openWaits
        .computeIfAbsent(id, scope -> new ConcurrentHashMap<>())
        .computeIfAbsent(
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
    // long-lived harness does not accumulate one entry per scope it has ever seen.
    if (waits.isEmpty()) {
      openWaits.remove(id, waits);
    }
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

  /** A counter, spelled as a zero-duration observation — see the class javadoc. */
  private void count(String name, AgentType agentType) {
    Observation.createNotStarted(name, registry)
        .lowCardinalityKeyValue(GEN_AI_AGENT_NAME, agentType.name())
        .start()
        .stop();
  }
}
