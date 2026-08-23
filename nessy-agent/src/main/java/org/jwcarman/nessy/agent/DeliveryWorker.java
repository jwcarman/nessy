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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.agent.codec.StateCodec;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.RetrySemantics;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.substrate.Codec;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.DocumentStore;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.spi.substrate.Versioned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivery is fold-advance (durable-deliveries spec §5): the one consumer of outbox deliveries. Per
 * each delivery: read and decode it, resolve the destination scope from its continuation, reconcile
 * the pending call with its outcome through the pure reducer, remember what the fold implies (see
 * below), commit one substrate batch — the CAS state write and the delivery's own removal, nothing
 * else (remembrance spec §1) — then dispatch the transition's effects (commit-before-dispatch,
 * unchanged law). A CAS miss re-reads and re-handles; {@link
 * org.jwcarman.nessy.agent.Transition#isIgnored()} means the batch is just the delivery removal.
 *
 * <p>An approval's {@code Allow} decision is not a completion — the tool has not run yet, so there
 * is no {@code ToolFinished} for the reducer to fold. That case dispatches the call directly
 * through {@link org.jwcarman.nessy.agent.spi.ToolCallExecutor#executeGrantedToolNow} — the
 * post-gate door — using the {@code CallAddress}/{@code ToolInvocationId} the grant's own
 * continuation carries. Re-entering the gate from the top (the old {@code ScopeRedrive}
 * unconditional re-fire, retired: nothing routes through it in production anymore) would re-run
 * policy and re-ask the approver on every grant (spec §5a).
 *
 * <p>One heartbeat thread per harness, started by the harness and stopped on {@link #close()};
 * {@link #nudge()} runs an immediate, synchronous drain after every completion — the heartbeat is
 * the recovery net, never the happy-path latency (spec §5).
 *
 * <p>The reaper is this worker's second sweep, on the same heartbeat (spec §6): scan {@code
 * computation} documents, decode each, and compare its deadline. Deadline-less computations are
 * skipped — they wait indefinitely, exactly like an approval. An overdue {@link
 * RetrySemantics#NON_RETRYABLE} computation is failed — {@code complete(id,
 * Failure("TIMEOUT_NON_RETRYABLE"))} — which rides the normal delivery pipeline into the fold, no
 * special timeout path. An overdue {@link RetrySemantics#RETRYABLE} computation gets its deadline
 * CAS-bumped (a lost CAS means another worker already won the bump, or already completed it — this
 * worker backs off) and is redispatched through {@link
 * org.jwcarman.nessy.agent.spi.ToolCallExecutor#executeGrantedToolNow}, the same {@code
 * ToolInvocationId} the computation already carries.
 *
 * <p>Memory has left the atomic batch (remembrance spec §1): every remembrance a fold implies is
 * remembered through {@link
 * org.jwcarman.nessy.spi.Memory#remember(org.jwcarman.nessy.spi.Remembrance)} BEFORE the commit
 * batch — {@code [state CAS, delivery delete]}, nothing else — ever runs. A throwing {@code
 * remember} aborts the attempt before that batch, leaving the delivery pending for natural redrive;
 * a successful {@code remember} that is later followed by a lost CAS on the batch just re-remembers
 * the same keys on retry, which converges by the SPI's own idempotence law. This is what makes ANY
 * {@link org.jwcarman.nessy.spi.Memory} — substrate-backed or a genuinely foreign store — a
 * first-class citizen here: this worker no longer inspects what kind of {@code Memory} a scope is
 * wired with.
 */
final class DeliveryWorker<O> implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
  private static final String STATE_KIND = "state";
  private static final String TIMEOUT_NON_RETRYABLE = "TIMEOUT_NON_RETRYABLE";
  private static final int SCAN_LIMIT = 1000;

  /**
   * The reap sweep's own key fetch, wider than {@link #SCAN_LIMIT} (F2, pre-dating kind-scoping):
   * fetching KEYS (not documents) is metadata-cheap, so a wider window here is a fair trade — it
   * does not raise the delivery sweep's own {@link #SCAN_LIMIT}, and it is still a bounded cap, not
   * the unbounded cursor the real fix needs. Approvals no longer share this kind at all
   * (computation-identity spec §3: {@code approval/<agentType>} is its own kind, never reaped,
   * never scanned here), so the width is now purely about a tool-computation backlog past this cap
   * still being able to starve the reaper — parked, per {@code
   * docs/concepts/durable-computation.md}'s Honest limits.
   */
  private static final int REAP_KEY_SCAN_LIMIT = 20_000;

  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);

  private final Substrate store;
  private final OutcomeCodec codec;
  private final Harness<O> harness;
  private final AgentBinder binder;
  private final SubstrateComputations computations;
  private final String computationKind;
  private final String outboxKind;
  private final ObjectMapper mapper;
  private final Thread heartbeat;

  /** The {@code outbox/<agentType>} kind, typed over this worker's own {@link OutcomeCodec}. */
  private final DocumentStore<OutcomeCodec.DeliveryDocument> outbox;

  /** The {@code computation/<agentType>} kind (execution only — spec §3), typed the same way. */
  private final DocumentStore<OutcomeCodec.PendingDocument> pendingComputations;

  /** The {@code state} kind, typed over {@link StateCodec} — the scope's phase. */
  private final DocumentStore<Phase> states;

  /**
   * The grant arm's single-winner mechanism (spec §5a invariant 5, fix round 2 item (c)): a bare
   * key set, {@code add} as the claim, {@code remove} as the release. In-process only — this is NOT
   * a substrate write, and does not protect against any other {@code DeliveryWorker} instance, in
   * this process or another, racing the same delivery (two workers in one JVM over one substrate
   * are just as unprotected as two workers in two JVMs — this is a plain in-memory set, not a
   * cross-instance coordination mechanism); it exists specifically to serialize THIS worker's own
   * {@link #nudge()} against its own heartbeat thread, the exact race a version-bump alone cannot
   * close (a second racer reading after the first's bump sees an ordinary document at a newer
   * version, indistinguishable from "untouched," and bumps it again just as validly).
   */
  private final Set<String> claiming = ConcurrentHashMap.newKeySet();

  private volatile boolean closed;

  DeliveryWorker(Substrate store, ObjectMapper mapper, Harness<O> harness, AgentResolver resolver) {
    this(store, mapper, harness, resolver, DEFAULT_POLL_INTERVAL);
  }

  DeliveryWorker(
      Substrate store,
      ObjectMapper mapper,
      Harness<O> harness,
      AgentResolver resolver,
      Duration pollInterval) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.codec = new OutcomeCodec(mapper);
    StateCodec stateCodec = new StateCodec(mapper);
    this.harness = Objects.requireNonNull(harness, "harness must not be null");
    Objects.requireNonNull(resolver, "resolver must not be null");
    this.binder = new ResolvingAgentBinder(resolver);
    this.computationKind = Kinds.computation(harness.type());
    this.outboxKind = Kinds.outbox(harness.type());
    this.computations = new SubstrateComputations(store, mapper, computationKind, outboxKind);
    this.outbox = store.document(outboxKind, deliveryDocumentCodec(codec));
    this.pendingComputations = store.document(computationKind, pendingDocumentCodec(codec));
    this.states = store.document(STATE_KIND, stateCodec(stateCodec));
    Objects.requireNonNull(pollInterval, "pollInterval must not be null");
    this.heartbeat = new Thread(() -> heartbeatLoop(pollInterval), "nessy-delivery");
    this.heartbeat.setDaemon(true);
  }

  /** Adapts {@link OutcomeCodec#toJson(OutcomeCodec.PendingDocument)}/{@code pendingDocument}. */
  private static Codec<OutcomeCodec.PendingDocument> pendingDocumentCodec(OutcomeCodec codec) {
    return new Codec<>() {
      @Override
      public byte[] encode(OutcomeCodec.PendingDocument value) {
        return codec.toJson(value).getBytes(StandardCharsets.UTF_8);
      }

      @Override
      public OutcomeCodec.PendingDocument decode(byte[] bytes) {
        return codec.pendingDocument(new String(bytes, StandardCharsets.UTF_8));
      }
    };
  }

  /** Adapts {@link OutcomeCodec#toJson(OutcomeCodec.DeliveryDocument)}/{@code deliveryDocument}. */
  private static Codec<OutcomeCodec.DeliveryDocument> deliveryDocumentCodec(OutcomeCodec codec) {
    return new Codec<>() {
      @Override
      public byte[] encode(OutcomeCodec.DeliveryDocument value) {
        return codec.toJson(value).getBytes(StandardCharsets.UTF_8);
      }

      @Override
      public OutcomeCodec.DeliveryDocument decode(byte[] bytes) {
        return codec.deliveryDocument(new String(bytes, StandardCharsets.UTF_8));
      }
    };
  }

  /** Adapts {@link StateCodec}'s String-JSON binding to the byte-oriented {@link Codec} seam. */
  private static Codec<Phase> stateCodec(StateCodec codec) {
    return new Codec<>() {
      @Override
      public byte[] encode(Phase value) {
        return codec.toJson(value).getBytes(StandardCharsets.UTF_8);
      }

      @Override
      public Phase decode(byte[] bytes) {
        return codec.phase(new String(bytes, StandardCharsets.UTF_8));
      }
    };
  }

  void start() {
    heartbeat.start();
  }

  /**
   * An immediate, synchronous drain — the happy path after every completion. Never throws: a
   * completing desk calls this after its own commit, and a nudge failure must not surface as if the
   * desk's own {@code complete()} had failed.
   */
  void nudge() {
    safeDrainOnce();
  }

  @Override
  public void close() {
    closed = true;
    heartbeat.interrupt();
  }

  private void heartbeatLoop(Duration pollInterval) {
    while (!closed) {
      try {
        Thread.sleep(pollInterval);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (!closed) {
        safeDrainOnce();
        safeReapOnce();
      }
    }
  }

  /** Guards the whole sweep: a scan-level failure must not kill the heartbeat thread. */
  private void safeDrainOnce() {
    try {
      drainOnce();
    } catch (RuntimeException e) {
      log.warn("a delivery sweep failed; will retry on the next heartbeat or nudge", e);
    }
  }

  /** Guards the whole reaper sweep: a scan-level failure must not kill the heartbeat thread. */
  private void safeReapOnce() {
    try {
      reapOnce();
    } catch (RuntimeException e) {
      log.warn("a reaper sweep failed; will retry on the next heartbeat", e);
    }
  }

  /**
   * One bad delivery — an undecodable continuation, an unknown outcome vocabulary, a resolver
   * failure — must not block every other pending delivery behind it (no head-of-line blocking), so
   * each is guarded individually and logged rather than left to abort the whole scan.
   */
  private void drainOnce() {
    for (String key : outbox.keys(SCAN_LIMIT)) {
      try {
        deliverOne(key);
      } catch (RuntimeException e) {
        log.warn("delivery {} could not be processed; skipped this sweep", key, e);
      }
    }
  }

  private void deliverOne(String key) {
    Optional<Versioned<OutcomeCodec.DeliveryDocument>> doc = outbox.read(key);
    if (doc.isEmpty()) {
      return; // already delivered by another drain — deliveries are pending-only (spec §4)
    }
    // spec §3: this worker's own outboxKind (outbox/<agentType>) never holds another harness
    // type's deliveries — isolation by construction, no runtime type peek needed anymore.
    OutcomeCodec.DeliveryDocument delivery = doc.get().value();
    ScopeRouting.Routing routing = ScopeRouting.decode(mapper, delivery.destination());
    AgentType type = AgentType.of(routing.agentType());
    AgentId id = AgentId.of(routing.agentId());
    Optional<ToolOutcome> toolOutcome = toToolOutcome(delivery.outcome());
    if (toolOutcome.isEmpty()) {
      deliverGrant(key, type, id, routing);
      return;
    }
    deliverCompletion(type, id, routing.call().id(), toolOutcome.get(), key);
  }

  /**
   * An approval grant is not a fold-advance: the tool has not run yet, so there is no {@code
   * ToolFinished} for the reducer to fold. The grant arm dispatches the call directly through
   * {@link org.jwcarman.nessy.agent.spi.ToolCallExecutor#executeGrantedToolNow} — the post-gate
   * door — using the {@code CallAddress}/{@code ToolInvocationId} the continuation itself carries;
   * no fold read, no re-derivation, and critically no policy/approval re-run (spec §5a). This
   * closed the Task 2 grant gap: the old {@code ScopeRedrive} unconditional re-fire (retired) used
   * to re-enter {@code RegistryToolCallExecutor}'s gate from the top, re-asking the approver on
   * every grant.
   *
   * <p><b>Transfer-then-dispatch (spec §5a invariant 5):</b> {@link #claiming} is the single-winner
   * mechanism — see its own javadoc for why a version-bump alone is not enough. Only the claim's
   * winner dispatches. An immediate outcome's grant delivery is then consumed by {@link
   * #foldGrantedResult}'s own batch — journal appends, state CAS, and the delivery's removal at the
   * current version, the same shape {@link #deliverCompletion} uses. A deferred outcome's transfer
   * — {@code [create tool computation, delete delivery]} — is composed into ONE {@link
   * Substrate#batch} by {@link ComputationDeferredToolCallPolicy#onDeferred} via the {@code
   * alsoCommit} door, before control returns here — so once that batch commits, there is no window
   * where the computation exists and this delivery still does, and no way for a real completion to
   * ever find this delivery left over to reprocess. This is NOT "closed at every committed point"
   * unqualified, though: the grant's OWN completion batch (approval computation deleted, this
   * delivery created) is itself a committed point at which neither the approval nor the tool
   * computation exists. That window is closed now (computation-identity spec §4): the grant
   * delivery sits at the completed approval computation's own deterministic id, and {@link
   * ComputationDeferredToolCallPolicy#pendingComputation} checks that exact key (via {@link
   * SubstrateComputations#deliveryPending}) before ever reaching the approver again — so a
   * staleness redrive landing between the grant's completion batch and this worker's drain absorbs
   * at the gate instead of re-asking.
   */
  private void deliverGrant(String key, AgentType type, AgentId id, ScopeRouting.Routing routing) {
    if (!claiming.add(key)) {
      return; // another drain in THIS process (nudge racing the heartbeat) already owns this key
    }
    try {
      deliverClaimedGrant(key, type, id, routing);
    } finally {
      claiming.remove(key);
    }
  }

  private void deliverClaimedGrant(
      String key, AgentType type, AgentId id, ScopeRouting.Routing routing) {
    // Non-decoding version() read (THE TOCTOU LESSON): only the CAS token is needed here, never
    // the decoded delivery — a decoding read() would widen this presence-check window in exactly
    // the racing hot path spec §1.5's carve-out was opened to close.
    OptionalLong currentVersion = outbox.version(key);
    if (currentVersion.isEmpty()) {
      return; // already delivered by another drain
    }
    CallAddress address =
        new CallAddress(
            routing.agentType(), routing.agentId(), routing.responseId(), routing.call().id());
    ToolInvocationId invocation = new ToolInvocationId(routing.responseId(), routing.call().id());
    Substrate.Op deleteOp = outbox.deleteOp(key, currentVersion.getAsLong());

    ToolCallExecutor executor = harness.toolExecutorFor(id);
    ToolExecution result =
        executor.executeGrantedToolNow(routing.call(), address, invocation, Optional.of(deleteOp));
    switch (result) {
      case ToolExecution.Immediate(ToolOutcome outcome) ->
          foldGrantedResult(type, id, routing.call(), outcome, key);
      case ToolExecution.Deferred(_) -> {
        // the transfer batch already committed [create tool computation, delete delivery] inside
        // onDeferred, using the deleteOp above — nothing left to do here
      }
    }
  }

  /**
   * The immediate arm of a grant (spec §5a): the tool already ran, synchronously, and its outcome
   * is in hand — so the grant delivery is consumed by the RESULT's own fold-advance batch, the same
   * shape {@link #deliverCompletion} uses, sourced from a directly-computed outcome instead of a
   * decoded outbox document, and deleting the GRANT delivery (not re-deriving a completion one) at
   * whatever version it currently reads at — always the claimed one, since {@link #deliverGrant}
   * claims before ever reaching here.
   */
  private void foldGrantedResult(
      AgentType type, AgentId id, ToolCall call, ToolOutcome outcome, String deliveryKey) {
    while (true) {
      // Non-decoding version() read (THE TOCTOU LESSON): only the CAS token this delete needs,
      // never the decoded delivery — see deliverClaimedGrant's own note on this same shape.
      OptionalLong deliveryVersion = outbox.version(deliveryKey);
      if (deliveryVersion.isEmpty()) {
        return; // another drain already delivered this delivery
      }
      State state = readState(id);
      var event = new AgentEvent.ToolFinished(call, outcome);
      var transition = state.phase().handle(event);
      if (!transition.isIgnored()) {
        // remember BEFORE the commit batch (remembrance spec §1 law 1): a throwing remember
        // propagates straight out of this method, before store.batch ever runs, leaving the
        // delivery pending for natural redrive.
        ToolFoldRemembrance.remember(
            harness.memoryFor(id), type, id, state.phase(), call, outcome, transition);
      }
      List<Substrate.Op> ops =
          foldOps(id, state, transition, deliveryKey, deliveryVersion.getAsLong());
      try {
        store.batch(ops);
      } catch (ConflictException _) {
        // lost the race — re-read state (or find the delivery already gone) and retry; the
        // remember above already ran, keyed by the call's execution ComputationId, so a retry
        // that remembers the same keys again converges (remembrance spec §1 law 2) rather than
        // duplicating anything.
        continue;
      }
      if (!transition.isIgnored()) {
        dispatchEffects(type, id, transition.next(), transition.effects());
      }
      return;
    }
  }

  private void deliverCompletion(
      AgentType type, AgentId id, String callId, ToolOutcome outcome, String deliveryKey) {
    while (true) {
      // Non-decoding version() read (THE TOCTOU LESSON): only the CAS token this delete needs.
      OptionalLong deliveryVersion = outbox.version(deliveryKey);
      if (deliveryVersion.isEmpty()) {
        return; // another drain already delivered this delivery
      }
      State state = readState(id);
      var call = routingCall(state, callId);
      var event = new AgentEvent.ToolFinished(call, outcome);
      var transition = state.phase().handle(event);
      if (!transition.isIgnored()) {
        // remember BEFORE the commit batch (remembrance spec §1 law 1): a throwing remember
        // propagates straight out of this method, before store.batch ever runs, leaving the
        // delivery pending for natural redrive.
        ToolFoldRemembrance.remember(
            harness.memoryFor(id), type, id, state.phase(), call, outcome, transition);
      }
      List<Substrate.Op> ops =
          foldOps(id, state, transition, deliveryKey, deliveryVersion.getAsLong());
      try {
        store.batch(ops);
      } catch (ConflictException _) {
        // lost the race — re-read state (or find the delivery already gone) and retry; the
        // remember above already ran, keyed by the call's execution ComputationId, so a retry
        // that remembers the same keys again converges (remembrance spec §1 law 2) rather than
        // duplicating anything.
        continue;
      }
      if (!transition.isIgnored()) {
        dispatchEffects(type, id, transition.next(), transition.effects());
      }
      return;
    }
  }

  /** The current scope state — a genuine value read, since the fold needs its decoded phase. */
  private State readState(AgentId id) {
    return states
        .read(id.value())
        .map(v -> new State(v.value(), v.version()))
        .orElseGet(State::initial);
  }

  /**
   * The one atomic batch a fold-advance commits (remembrance spec §1): the CAS state write and the
   * delivery's own removal — memory has left this batch entirely; every remembrance the transition
   * implied was already remembered by the caller before this method ever runs.
   */
  private List<Substrate.Op> foldOps(
      AgentId id, State state, Transition transition, String deliveryKey, long deliveryVersion) {
    List<Substrate.Op> ops = new ArrayList<>();
    if (!transition.isIgnored()) {
      ops.add(states.writeOp(id.value(), transition.next(), state.version()));
    }
    ops.add(outbox.deleteOp(deliveryKey, deliveryVersion));
    return ops;
  }

  /**
   * One bad computation — an undecodable continuation, an unresolvable scope — must not block every
   * other pending computation behind it, matching {@link #drainOnce()}'s per-item isolation.
   * Package-visible (not {@code private}) so tests can trigger one reaper sweep synchronously,
   * without a real-time heartbeat wait — the same reasoning {@link #nudge()} exists for the
   * delivery sweep, just without a public door of its own (the reaper's public entry stays the
   * heartbeat; nothing external calls this directly).
   *
   * <p>Approvals no longer share this kind at all (computation-identity spec §3): {@code
   * approval/<agentType>} is its own kind, so this sweep — scanning {@link #computationKind}
   * (execution only) — never sees one to skip, and never sees another harness type's computations
   * either, since {@link #computationKind} is this worker's own {@code computation/<agentType>}.
   * Isolation and the deadline-less-approval exclusion are both by construction now; neither needs
   * a runtime filter over the keys this sweep fetches.
   */
  void reapOnce() {
    for (String key : pendingComputations.keys(REAP_KEY_SCAN_LIMIT)) {
      try {
        reapOne(key);
      } catch (RuntimeException e) {
        log.warn("computation {} could not be reaped; skipped this sweep", key, e);
      }
    }
  }

  private void reapOne(String key) {
    Optional<Versioned<OutcomeCodec.PendingDocument>> doc = pendingComputations.read(key);
    if (doc.isEmpty()) {
      return; // completed (or never existed) by the time this sweep reached it
    }
    OutcomeCodec.PendingDocument pending = doc.get().value();
    Optional<Instant> deadline = pending.deadline();
    if (deadline.isEmpty() || deadline.get().isAfter(Instant.now())) {
      return; // deadline-less waits indefinitely (spec §6); not yet overdue waits its turn
    }
    ComputationId id = ComputationId.of(key);
    ScopeRouting.Routing routing = ScopeRouting.decode(mapper, pending.returnAddress());
    if (routing.retrySemantics() == RetrySemantics.NON_RETRYABLE) {
      computations.complete(id, new Outcome.Failure(TIMEOUT_NON_RETRYABLE));
      nudge(); // the failure rides the normal delivery pipeline into the fold (spec §6)
      return;
    }
    reapRetryable(key, doc.get().version(), pending, routing);
  }

  /**
   * {@code RETRYABLE} overdue: CAS-bump the deadline, then redispatch the same {@code
   * ToolInvocationId} through {@link
   * org.jwcarman.nessy.agent.spi.ToolCallExecutor#executeGrantedToolNow} — the post-gate door,
   * synchronously on this sweep's own thread, since this call already cleared the gate when it
   * first deferred (spec §5a, §6). A lost CAS on the bump means another worker already bumped or
   * completed this computation first; this sweep backs off rather than double-dispatching.
   *
   * <p>F2: a {@code RETRYABLE} tool that answers immediately on redispatch ({@link Awaited.Ready})
   * must not orphan its own computation — an unbounded reaper loop and an ever-growing computation
   * table otherwise, since nothing would ever delete it. The immediate outcome is completed
   * straight into the pipeline here — {@code computations.complete(id, outcome)} then {@link
   * #nudge()} — exactly as the normal delivery arm folds any other completion.
   */
  private void reapRetryable(
      String key,
      long version,
      OutcomeCodec.PendingDocument pending,
      ScopeRouting.Routing routing) {
    Duration timeout =
        routing
            .timeout()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "a RETRYABLE overdue computation had a deadline but no timeout on its"
                            + " continuation — invariant violated: a deadline implies a timeout"
                            + " (durable-deliveries spec §6)"));
    Instant bumped = Instant.now().plus(timeout);
    OutcomeCodec.PendingDocument bumpedPending =
        new OutcomeCodec.PendingDocument(
            pending.invocation(), pending.returnAddress(), Optional.of(bumped));
    try {
      pendingComputations.write(key, bumpedPending, version);
    } catch (ConflictException _) {
      return; // another worker's bump or completion won the race
    }
    CallAddress address =
        new CallAddress(
            routing.agentType(),
            routing.agentId(),
            pending.invocation().responseId(),
            routing.call().id());
    ToolExecution result =
        harness
            .toolExecutorFor(AgentId.of(routing.agentId()))
            .executeGrantedToolNow(routing.call(), address, pending.invocation(), Optional.empty());
    switch (result) {
      case ToolExecution.Immediate(ToolOutcome outcome) -> {
        computations.complete(ComputationId.of(key), toOutcome(outcome));
        nudge(); // the result rides the normal delivery pipeline into the fold (spec §6)
      }
      case ToolExecution.Deferred(_) -> {
        // re-parked; already durable via its own create() inside onDeferred
      }
    }
  }

  /**
   * The pending call, re-derived from the currently-loaded phase's outstanding effects — the same
   * derivation {@link org.jwcarman.nessy.agent.DefaultAgent#redispatch()} relies on. Falls back to
   * a synthetic zero-argument call only if the phase no longer carries it (an already-reconciled or
   * stale delivery); the reducer's own dedup then ignores the event regardless of the call's shape.
   */
  private ToolCall routingCall(State state, String callId) {
    for (Effect effect : state.phase().outstandingEffects()) {
      if (effect instanceof Effect.ExecuteTool(var call) && call.id().equals(callId)) {
        return call;
      }
    }
    return new ToolCall(callId, "unknown", JsonNodeFactory.instance.objectNode());
  }

  /**
   * {@code phase} is the transition's committed {@code next()} — an {@code ExecuteTool} effect here
   * would need its {@code ModelResponseId} from an {@link
   * org.jwcarman.nessy.agent.Phase.AwaitingTools}, but a {@code ToolFinished} fold never actually
   * emits one (only a model response does, in {@code AwaitingModel}'s own handling); this arm stays
   * total rather than assuming that invariant silently.
   */
  private void dispatchEffects(AgentType type, AgentId id, Phase phase, List<Effect> effects) {
    for (Effect effect : effects) {
      switch (effect) {
        case Effect.CallModel _ ->
            harness.modelExecutorFor(id).callModel(event -> binder.deliver(type, id, event));
        case Effect.ExecuteTool(var call) ->
            harness
                .toolExecutorFor(id)
                .executeTool(call, responseIdOf(phase), event -> binder.deliver(type, id, event));
      }
    }
  }

  private static ModelResponseId responseIdOf(Phase phase) {
    if (phase instanceof Phase.AwaitingTools awaiting) {
      return awaiting.responseId();
    }
    throw new IllegalStateException(
        "an ExecuteTool effect was dispatched outside AwaitingTools: " + phase);
  }

  /**
   * {@code Success(Decision.Allow)} is empty — a grant, not a completion; every other outcome
   * (including a denial, whose reason becomes the tool's in-band failure) maps to a {@link
   * ToolOutcome}. {@link Outcome.Success#value()} is a data-born {@link
   * com.fasterxml.jackson.databind.JsonNode} now (computation-identity spec §2 addendum), so this
   * decodes it back to its domain payload through {@link #codec} before switching on it — inlined
   * here (rather than delegated with a fallback default) so a future {@link Outcome} variant fails
   * this switch at compile time.
   */
  private Optional<ToolOutcome> toToolOutcome(Outcome outcome) {
    return switch (outcome) {
      case Outcome.Success(var payload) -> toToolOutcome(codec.decodeSuccess(payload));
      case Outcome.Failure(String message) ->
          Optional.of(new ToolOutcome.Failed(new ToolError(message)));
      case Outcome.Cancelled(String reason) ->
          Optional.of(new ToolOutcome.Failed(new ToolError("cancelled: " + reason)));
    };
  }

  /**
   * The reverse mapping: a reaper redispatch that answers immediately (spec §6, F2) rides this into
   * {@code complete(id, outcome)} so its computation is consumed by the normal pipeline rather than
   * orphaned — the replacement for the retired {@code DurableOutcomes.toOutcome}, now that building
   * an {@code Outcome.Success} needs {@link #codec}'s pinned-mapper encoding.
   */
  private Outcome toOutcome(ToolOutcome outcome) {
    return switch (outcome) {
      case ToolOutcome.Returned(ToolResult result) ->
          new Outcome.Success(codec.encodeSuccess(result));
      case ToolOutcome.Failed(ToolError error) -> new Outcome.Failure(error.message());
    };
  }

  private static Optional<ToolOutcome> toToolOutcome(Object value) {
    return switch (value) {
      case Decision.Allow _ -> Optional.empty();
      case Decision.Deny(String reason) ->
          Optional.of(new ToolOutcome.Failed(new ToolError(reason)));
      case ToolResult result -> Optional.of(new ToolOutcome.Returned(result));
      default ->
          Optional.of(
              new ToolOutcome.Failed(
                  new ToolError("unexpected durable payload: " + value.getClass().getName())));
    };
  }
}
