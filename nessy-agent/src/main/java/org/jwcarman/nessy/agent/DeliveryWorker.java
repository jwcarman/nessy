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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.api.Backoff;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.api.Lease;
import org.jwcarman.continuum.api.ResultTtl;
import org.jwcarman.continuum.api.TypedDelivery;
import org.jwcarman.continuum.api.TypedOutcome;
import org.jwcarman.nessy.agent.codec.StateCodec;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.DocumentStore;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivery is fold-advance (durable-deliveries spec §5): the approval and tool kinds' own consumer.
 * Per each delivery: resolve the destination scope from its routing, reconcile the pending call
 * with its outcome through the pure reducer, remember what the fold implies (see below), commit one
 * substrate batch — the CAS state write and, for the tool kind, the dispatch index entry's own
 * deletion (spec §5) — then dispatch the transition's effects (commit-before-dispatch, unchanged
 * law). A CAS miss re-reads and re-handles; {@link org.jwcarman.nessy.agent.Transition#isIgnored()}
 * means the batch is just the index entry's deletion.
 *
 * <p>An approval's {@code Allow} decision is not a completion — the tool has not run yet, so there
 * is no {@code ToolFinished} for the reducer to fold. That case dispatches the call directly
 * through {@link org.jwcarman.nessy.agent.spi.ToolCallExecutor#executeGrantedToolNow} — the
 * post-gate door — using the {@code CallAddress}/{@code ToolInvocationId} the grant's own
 * continuation carries. Re-entering the gate from the top (the old {@code ScopeRedrive}
 * unconditional re-fire, retired: nothing routes through it in production anymore) would re-run
 * policy and re-ask the approver on every grant (spec §5a).
 *
 * <p>Pumping moved off a per-harness daemon heartbeat onto one shared {@link ComputationScheduler}
 * (continuum-adoption spec §7): this worker implements {@link ComputationPump}, and {@link
 * ComputationScheduler#register} schedules its six pumps — deliver, expire, purge, once each for
 * the approval and tool kinds — with {@code scheduleWithFixedDelay}. {@link #nudge()} still exists
 * for the happy path: it submits one drain pass of each kind to the shared scheduler, so a
 * completing desk (e.g. {@code ApprovalDesk#approve}) returns immediately rather than blocking for
 * as long as a granted inline tool takes to run. The old in-substrate outbox scan and its reaper
 * (durable-deliveries spec §6) are retired (continuum-adoption spec §6): both kinds now live on
 * Continuum's own {@link ContinuumClient}, which claims, leases, and expires deliveries itself —
 * nothing in this worker scans a Substrate outbox or a computation kind for overdue work anymore.
 *
 * <p>Memory has left the atomic batch (remembrance spec §1): every remembrance a fold implies is
 * remembered through {@link
 * org.jwcarman.nessy.spi.Memory#remember(org.jwcarman.nessy.spi.Remembrance)} BEFORE the commit
 * batch — the state CAS and, for the tool kind, the dispatch index entry's own deletion — ever
 * runs. A throwing {@code remember} aborts the attempt before that batch; Continuum's own {@link
 * ContinuumClient#deliverResults} catches the exception and releases the delivery for a later retry
 * (its own backoff, not a Substrate redrive). A successful {@code remember} that is later followed
 * by a lost CAS on the batch just re-remembers the same keys on retry, which converges by the SPI's
 * own idempotence law. This is what makes ANY {@link org.jwcarman.nessy.spi.Memory} —
 * substrate-backed or a genuinely foreign store — a first-class citizen here: this worker no longer
 * inspects what kind of {@code Memory} a scope is wired with.
 */
final class DeliveryWorker<O> implements ComputationPump {

  private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
  private static final String STATE_KIND = "state";

  /**
   * The approval kind's lease and backoff (continuum-adoption spec §11.2, ruled): long enough for
   * an approval-gated {@code Awaited.Ready} tool to complete synchronously inside the grant
   * consumer — a human just approved it interactively, so a minute or two is generous, not a guess.
   */
  private static final Lease APPROVAL_LEASE = Lease.ofMinutes(2);

  private static final Backoff APPROVAL_BACKOFF = Backoff.ofSeconds(30);

  /** The approval sweep's own per-pass cap — small, since a human-gated backlog is never large. */
  private static final BatchSize APPROVAL_BATCH_SIZE = BatchSize.of(100);

  /**
   * The tool kind's lease (continuum-adoption spec §11.2, ruled): short, because this consumer only
   * folds — it never runs a tool inline the way the approval consumer's grant arm does, so there is
   * no slow in-flight work a lease must outlast.
   */
  private static final Lease TOOL_LEASE = Lease.ofSeconds(30);

  private static final Backoff TOOL_BACKOFF = Backoff.ofSeconds(5);

  /** The tool sweep's own per-pass cap. */
  private static final BatchSize TOOL_BATCH_SIZE = BatchSize.of(100);

  private final Substrate store;
  private final Harness<O> harness;
  private final AgentBinder binder;

  /**
   * Where {@link #nudge()} submits its post-completion approval/tool drain passes (continuum-
   * adoption spec §7) — the production door ({@link Harness#of}) hands in the shared {@link
   * ComputationScheduler} itself (it doubles as an {@link Executor}); a pre-migration test
   * constructor that never wires Continuum hands in a direct {@code Runnable::run} executor, which
   * is never actually reached since {@link #approvalClient}/{@link #toolClient} are null there too.
   */
  private final Executor nudgeExecutor;

  /** The {@code state} kind, typed over {@link StateCodec} — the scope's phase. */
  private final DocumentStore<Phase> states;

  /**
   * The approval kind's Continuum client (continuum-adoption spec §3, §7) and its dispatch index —
   * both {@code null} for a worker built by a pre-migration test constructor that never wires
   * approvals; {@link #drainApprovals} and its callers no-op when either is absent rather than
   * assuming every worker in this module's test suite has been repointed at Continuum yet.
   */
  private final ContinuumClient<Decision, Routing> approvalClient;

  private final DispatchIndex dispatchIndex;

  /**
   * The tool kind's Continuum client (continuum-adoption spec §3, §7) — {@code null} for a worker
   * built by a pre-migration test constructor that never wires tools; {@link #drainTools} and its
   * callers no-op when absent, mirroring {@link #approvalClient}.
   */
  private final ContinuumClient<ToolResult, Routing> toolClient;

  /**
   * The pre-migration test shape: no Continuum wiring, {@link #nudge()}'s approval/tool submissions
   * are guarded no-ops (spec §3), and this worker is never {@link ComputationScheduler#register}ed
   * — nothing ever calls the four expiry/purge {@link ComputationPump} methods on it.
   */
  DeliveryWorker(Substrate store, ObjectMapper mapper, Harness<O> harness, AgentResolver resolver) {
    this(store, mapper, harness, resolver, Runnable::run, null, null, null);
  }

  /**
   * The production shape (continuum-adoption spec §3, §7): a worker wired for the approval and tool
   * kinds' Continuum clients, their shared dispatch index, and the {@link Executor} {@link
   * #nudge()}'s post-completion drain passes submit to — {@link Harness#of} hands in the same
   * shared {@link ComputationScheduler} it registers this worker's six pumps with.
   *
   * @param nudgeExecutor where {@link #nudge()} submits its approval/tool drain passes
   */
  DeliveryWorker(
      Substrate store,
      ObjectMapper mapper,
      Harness<O> harness,
      AgentResolver resolver,
      Executor nudgeExecutor,
      ContinuumClient<Decision, Routing> approvalClient,
      DispatchIndex dispatchIndex,
      ContinuumClient<ToolResult, Routing> toolClient) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    Objects.requireNonNull(mapper, "mapper must not be null");
    StateCodec stateCodec = new StateCodec(mapper);
    this.harness = Objects.requireNonNull(harness, "harness must not be null");
    Objects.requireNonNull(resolver, "resolver must not be null");
    this.binder = new ResolvingAgentBinder(resolver);
    this.states = store.document(STATE_KIND, stateCodec(stateCodec));
    this.nudgeExecutor = Objects.requireNonNull(nudgeExecutor, "nudgeExecutor must not be null");
    this.approvalClient = approvalClient;
    this.dispatchIndex = dispatchIndex;
    this.toolClient = toolClient;
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

  /**
   * The happy path after every completion (continuum-adoption spec §7). Never throws: a completing
   * desk calls this after its own commit, and a nudge failure must not surface as if the desk's own
   * {@code complete()} had failed.
   *
   * <p>The approval and tool kinds' own Continuum drains are SUBMITTED to {@link #nudgeExecutor}
   * rather than run on the caller's thread: an approval or completion arriving from a UI or HTTP
   * handler should not block for as long as a granted inline tool takes to run.
   */
  void nudge() {
    submitApprovalsDrain();
    submitToolsDrain();
  }

  /**
   * Submits the approval kind's own drain pass (continuum-adoption spec §7): a no-op when this
   * worker was built by a pre-migration test constructor that never wired {@link #approvalClient}.
   */
  private void submitApprovalsDrain() {
    if (approvalClient == null) {
      return;
    }
    submit(guarded(() -> drainApprovals(APPROVAL_BATCH_SIZE)));
  }

  /**
   * Submits the tool kind's own drain pass (continuum-adoption spec §7): a no-op when this worker
   * was built by a pre-migration test constructor that never wired {@link #toolClient}.
   */
  private void submitToolsDrain() {
    if (toolClient == null) {
      return;
    }
    submit(guarded(() -> drainTools(TOOL_BATCH_SIZE)));
  }

  /**
   * {@link #nudge()}'s own "never throws" promise (fix round 2, item 2a) must hold for WHATEVER
   * {@link Executor} was injected at construction, not merely for {@code ComputationScheduler}'s
   * own (already-guarded) {@code execute}: a caller-supplied {@link Executor} that has been shut
   * down throws {@link RejectedExecutionException} straight out of {@code execute} itself — before
   * {@code task} (already wrapped by {@link #guarded}) ever gets a chance to run and be guarded by
   * that wrapper. A rejected nudge is exactly as benign as a rejected scheduled pump: the commit
   * this nudge follows already succeeded, so the drain simply waits for the next nudge or the next
   * scheduled tick instead.
   */
  private void submit(Runnable task) {
    try {
      nudgeExecutor.execute(task);
    } catch (RejectedExecutionException e) {
      log.debug("a nudge-submitted drain pass was rejected; the executor has been shut down", e);
    }
  }

  /** A thrown {@link RuntimeException} is logged, not propagated to {@link #nudgeExecutor}. */
  private static Runnable guarded(Runnable task) {
    return () -> {
      try {
        task.run();
      } catch (RuntimeException e) {
        log.warn("a nudge-submitted delivery sweep failed", e);
      }
    };
  }

  /**
   * The approval kind's own consumer (continuum-adoption spec §7): {@code Success(Allow)} runs the
   * tool through {@link org.jwcarman.nessy.agent.spi.ToolCallExecutor#executeGrantedToolNow} —
   * exactly the post-gate door the old grant arm below uses — while {@code Success(Deny)}, {@code
   * Failure}, and {@code Expired} all fold a tool failure so the model reads it in-band. Continuum
   * acknowledges the delivery once this consumer returns normally; a thrown exception releases the
   * claim immediately, backed off by {@link #APPROVAL_BACKOFF} (30s) — not held for the full {@link
   * #APPROVAL_LEASE} (2m) — so a failing fold re-fires a granted tool's side effect within 30
   * seconds, not two minutes.
   *
   * @param batchSize how many approval deliveries to claim in one pass
   * @return how many deliveries this pass processed
   */
  @Override
  public int drainApprovals(BatchSize batchSize) {
    return approvalClient.deliverResults(
        batchSize,
        APPROVAL_LEASE,
        APPROVAL_BACKOFF,
        delivery -> {
          Routing routing = delivery.continuation();
          AgentType type = AgentType.of(routing.agentType());
          AgentId id = AgentId.of(routing.agentId());
          CallAddress address =
              new CallAddress(
                  routing.agentType(),
                  routing.agentId(),
                  routing.responseId(),
                  routing.call().id());
          String computationId = delivery.computationId().value().toString();
          switch (delivery.outcome()) {
            case TypedOutcome.Success<Decision> success ->
                handleApprovalDecision(type, id, address, routing, computationId, success.value());
            case TypedOutcome.Failure<Decision> failure ->
                foldApprovalFailure(
                    type, id, address, computationId, routing.call(), failure.message());
            case TypedOutcome.Expired<Decision> expired ->
                foldApprovalFailure(
                    type,
                    id,
                    address,
                    computationId,
                    routing.call(),
                    expired.kind() + ": " + expired.message());
          }
        });
  }

  /**
   * The tool kind's own consumer (continuum-adoption spec §3, §7, §11.2): unlike the approval
   * consumer, this one never runs a tool inline — it only folds an already-produced {@link
   * ToolResult} (or a failure/expiry) into the scope, which is why its lease ({@link #TOOL_LEASE},
   * 30s) can be short.
   *
   * @param batchSize how many tool deliveries to claim in one pass
   * @return how many deliveries this pass processed
   */
  @Override
  public int drainTools(BatchSize batchSize) {
    return toolClient.deliverResults(batchSize, TOOL_LEASE, TOOL_BACKOFF, this::foldOutcome);
  }

  /**
   * Resolves {@code delivery}'s scope coordinates and folds its outcome through the reducer.
   * Package-visible (not {@code private}) so a test can drive a genuine redelivery of the same
   * outcome without a second pass through Continuum's own lease/ack cycle.
   */
  void foldOutcome(TypedDelivery<Routing, ToolResult> delivery) {
    Routing routing = delivery.continuation();
    AgentType type = AgentType.of(routing.agentType());
    AgentId id = AgentId.of(routing.agentId());
    CallAddress address =
        new CallAddress(
            routing.agentType(), routing.agentId(), routing.responseId(), routing.call().id());
    String computationId = delivery.computationId().value().toString();
    foldToolOutcome(
        type, id, address, computationId, routing.call(), toToolOutcome(delivery.outcome()));
  }

  /**
   * Expires up to a batch of the approval kind's overdue computations (continuum-adoption spec §7):
   * delegates straight to {@link ContinuumClient#failExpiredComputations(BatchSize)} — the approval
   * kind is non-retryable, so an overdue wait always ends, never restarts the work.
   *
   * @param batchSize the maximum expired approvals to process
   * @return the number expired
   */
  @Override
  public int expireApprovals(BatchSize batchSize) {
    return approvalClient.failExpiredComputations(batchSize);
  }

  /**
   * Expires up to a batch of the tool kind's overdue computations (continuum-adoption spec §7):
   * delegates straight to {@link ContinuumClient#failExpiredComputations(BatchSize)}.
   *
   * @param batchSize the maximum expired tool computations to process
   * @return the number expired
   */
  @Override
  public int expireTools(BatchSize batchSize) {
    return toolClient.failExpiredComputations(batchSize);
  }

  /**
   * Purges up to a batch of the approval kind's memoized results older than {@code ttl}
   * (continuum-adoption spec §7): delegates straight to {@link
   * ContinuumClient#purgeExpiredResults(BatchSize, ResultTtl)}.
   *
   * @param batchSize the maximum result records to delete
   * @param ttl how long results outlive completion
   * @return the number purged
   */
  @Override
  public int purgeApprovals(BatchSize batchSize, ResultTtl ttl) {
    return approvalClient.purgeExpiredResults(batchSize, ttl);
  }

  /**
   * Purges up to a batch of the tool kind's memoized results older than {@code ttl} (continuum-
   * adoption spec §7): delegates straight to {@link ContinuumClient#purgeExpiredResults(BatchSize,
   * ResultTtl)}.
   *
   * @param batchSize the maximum result records to delete
   * @param ttl how long results outlive completion
   * @return the number purged
   */
  @Override
  public int purgeTools(BatchSize batchSize, ResultTtl ttl) {
    return toolClient.purgeExpiredResults(batchSize, ttl);
  }

  /**
   * The tool kind's own fold-advance (mirrors {@link #foldApprovalResult}'s shape): read state,
   * reduce, remember, commit one {@link Substrate#batch} — the state CAS and the dispatch index
   * entry's own deletion (spec §5, §11.3) — then dispatch effects. There is no outbox delivery to
   * delete here: Continuum's own delivery is acknowledged by this consumer returning normally.
   *
   * <p>The dispatch entry's deletion is identity-checked against {@code computationId} ({@link
   * #foldOps}): the fold itself always applies (the reducer's own duplicate-call-id ignore already
   * absorbs a stale redelivery), but deleting an entry that has since been overwritten to name a
   * different, still-live computation would reopen the absorption door that entry exists to close.
   */
  private void foldToolOutcome(
      AgentType type,
      AgentId id,
      CallAddress address,
      String computationId,
      ToolCall call,
      ToolOutcome outcome) {
    while (true) {
      State state = warnIfNoStoredState(id, readState(id));
      var event = new AgentEvent.ToolFinished(call, outcome);
      var transition = state.phase().handle(event);
      if (!transition.isIgnored()) {
        ToolFoldRemembrance.remember(
            harness.memoryFor(id), type, id, state.phase(), call, outcome, transition);
      }
      List<Substrate.Op> ops = foldOps(id, state, transition, address, computationId);
      if (!ops.isEmpty()) {
        try {
          store.batch(ops);
        } catch (ConflictException _) {
          // lost the race — re-read state (or find the index entry already gone) and retry; the
          // remember above already ran, keyed by the call's own address, so a retry that remembers
          // the same keys again converges rather than duplicating anything.
          continue;
        }
      }
      if (!transition.isIgnored()) {
        dispatchEffects(type, id, transition.next(), transition.effects());
      }
      return;
    }
  }

  private void handleApprovalDecision(
      AgentType type,
      AgentId id,
      CallAddress address,
      Routing routing,
      String computationId,
      Decision decision) {
    switch (decision) {
      case Decision.Allow _ -> deliverApprovalGrant(type, id, address, routing, computationId);
      case Decision.Deny(String reason) ->
          foldApprovalFailure(type, id, address, computationId, routing.call(), reason);
    }
  }

  /**
   * The stale-grant guard (continuum-adoption spec §5, §11.3), shipped in its STRONG form: a grant
   * is admitted iff the call's dispatch entry currently exists and names THIS EXACT computation —
   * an identity check, not a predicate on the address. 0.1.0's typed {@code deliverResults}
   * consumer withheld the delivery's own {@code computationId} ({@code CompletionDelivery} carried
   * it, but the typed layer did not pass it through), so an earlier form of this guard could only
   * ask whether the call's dispatch entry currently existed and was APPROVAL-kind — a predicate
   * that discriminated finished calls from unfinished ones, not real approvals from orphans. 0.3.0
   * puts {@code computationId} on {@link TypedDelivery} itself, closing that gap.
   *
   * <p>{@link #foldApprovalFailure} (deny, {@code Failure}, {@code Expired}) carries the same
   * identity check, closing spec §11.3 gap 3: an orphan that hits its deadline while the real
   * approval is still live is now acknowledged, not folded over the live call.
   */
  private void deliverApprovalGrant(
      AgentType type, AgentId id, CallAddress address, Routing routing, String computationId) {
    if (!isCurrentDispatch(address, computationId)) {
      return; // a stale grant from an orphaned approval — acknowledged, not run
    }
    ToolExecution result =
        harness.toolExecutorFor(id).executeGrantedToolNow(routing.call(), address);
    switch (result) {
      case ToolExecution.Immediate(ToolOutcome outcome) ->
          foldApprovalResult(type, id, address, routing.call(), outcome);
      case ToolExecution.Deferred(_) -> {
        // the tool went durable via the tool kind's own ContinuumClient; onDeferred already
        // overwrote this call's dispatch entry to TOOL (unconditionally, on every deferral) before
        // control returned here — nothing left to do (spec §11.3 gap 2, closed).
      }
    }
  }

  /**
   * The deny/failure/expiry arm of the stale-grant guard (spec §11.3 gap 3, closed by this task):
   * an orphaned approval's own failure or expiry must not fold over the live call and delete its
   * dispatch entry out from under it — that would silently swallow the real approval's eventual
   * grant (entry gone, indistinguishable from "already handled").
   */
  private void foldApprovalFailure(
      AgentType type,
      AgentId id,
      CallAddress address,
      String computationId,
      ToolCall call,
      String reason) {
    if (!isCurrentDispatch(address, computationId)) {
      return; // a stale failure/expiry from an orphaned approval — acknowledged, not folded
    }
    foldApprovalResult(type, id, address, call, new ToolOutcome.Failed(new ToolError(reason)));
  }

  /**
   * Whether {@code address}'s dispatch entry currently names {@code computationId} — the stale-
   * grant guard's identity check (spec §11.3), shared by the approval kind's grant and
   * failure/expiry arms and the tool kind's dispatch-entry deletion.
   */
  private boolean isCurrentDispatch(CallAddress address, String computationId) {
    return dispatchIndex
        .find(address)
        .map(DispatchEntry::computationId)
        .filter(computationId::equals)
        .isPresent();
  }

  /**
   * The approval kind's own fold-advance (mirrors {@link #deliverCompletion}'s shape): read state,
   * reduce, remember, commit one {@link Substrate#batch} — the state CAS and the dispatch index
   * entry's own deletion, so the entry never outlives the call it named (the same reasoning {@link
   * #foldOps} documents for the outbox delete) — then dispatch effects.
   */
  private void foldApprovalResult(
      AgentType type, AgentId id, CallAddress address, ToolCall call, ToolOutcome outcome) {
    while (true) {
      State state = warnIfNoStoredState(id, readState(id));
      var event = new AgentEvent.ToolFinished(call, outcome);
      var transition = state.phase().handle(event);
      if (!transition.isIgnored()) {
        ToolFoldRemembrance.remember(
            harness.memoryFor(id), type, id, state.phase(), call, outcome, transition);
      }
      List<Substrate.Op> ops = new ArrayList<>();
      if (!transition.isIgnored()) {
        ops.add(states.writeOp(id.value(), transition.next(), state.version()));
      }
      dispatchIndex.deleteOp(address).ifPresent(ops::add);
      if (!ops.isEmpty()) {
        try {
          store.batch(ops);
        } catch (ConflictException _) {
          // lost the race — re-read state (or find the index entry already gone) and retry; the
          // remember above already ran, keyed by the call's own address, so a retry that remembers
          // the same keys again converges rather than duplicating anything.
          continue;
        }
      }
      if (!transition.isIgnored()) {
        dispatchEffects(type, id, transition.next(), transition.effects());
      }
      return;
    }
  }

  /**
   * The current scope state — a genuine value read, since the fold needs its decoded phase. Empty
   * means no state has ever been stored for this scope: ordinarily a genuine "never seen this scope
   * before", but also the exact shape a §11.1 durability mismatch takes — a durable computation
   * store handing this consumer a delivery for a scope whose substrate-backed state was never
   * durable in the first place. {@link #warnIfNoStoredState} is where that distinction gets logged;
   * this method just reports what it found.
   */
  private Optional<State> readState(AgentId id) {
    return states.read(id.value()).map(v -> new State(v.value(), v.version()));
  }

  /**
   * Guard 2 (continuum-adoption spec §11.1): a delivery folding against a scope with no stored
   * state is, absent any other explanation, the moment a tool result is silently dropped — {@link
   * Phase.Idle#handle(AgentEvent)} ignores it, indistinguishable from an ordinary
   * duplicate-delivery ignore unless this logs it first. Falls back to {@link State#initial()}
   * either way, so the fold proceeds exactly as it always has.
   */
  private State warnIfNoStoredState(AgentId id, Optional<State> stored) {
    if (stored.isEmpty()) {
      log.warn(
          "a delivery folded against scope {} with no stored state — either its first-ever"
              + " delivery, or a durability mismatch (spec §11.1) dropped the tool result this"
              + " delivery was meant to complete",
          id.value());
    }
    return stored.orElseGet(State::initial);
  }

  /**
   * The tool kind's own fold-advance batch (continuum-adoption spec §5, §11.3): the CAS state write
   * and the dispatch index entry's own deletion — there is no outbox delivery to delete here, since
   * Continuum owns the tool kind's delivery and acknowledges it by this consumer returning. The
   * deletion is identity-checked against {@code computationId}: a stale redelivery of an
   * already-superseded tool computation must not delete an entry that has since been overwritten to
   * name a different, still-live computation (spec §11.3 gap 3's tool-kind shape).
   */
  private List<Substrate.Op> foldOps(
      AgentId id, State state, Transition transition, CallAddress address, String computationId) {
    List<Substrate.Op> ops = new ArrayList<>();
    if (!transition.isIgnored()) {
      ops.add(states.writeOp(id.value(), transition.next(), state.version()));
    }
    if (isCurrentDispatch(address, computationId)) {
      dispatchIndex.deleteOp(address).ifPresent(ops::add);
    }
    return ops;
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
   * The tool kind's own outcome mapping (continuum-adoption spec §3, §7): {@code Success} carries
   * the tool's own answer straight through; {@code Failure} and {@code Expired} both fold an
   * in-band failure so the model reads it.
   */
  private static ToolOutcome toToolOutcome(TypedOutcome<ToolResult> outcome) {
    return switch (outcome) {
      case TypedOutcome.Success<ToolResult> success -> new ToolOutcome.Returned(success.value());
      case TypedOutcome.Failure<ToolResult> failure ->
          new ToolOutcome.Failed(new ToolError(failure.message()));
      case TypedOutcome.Expired<ToolResult> expired ->
          new ToolOutcome.Failed(new ToolError(expired.kind() + ": " + expired.message()));
    };
  }
}
