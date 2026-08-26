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
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.DocumentStore;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivery is fold-advance (durable-deliveries spec §5): the approval and tool kinds' own consumer.
 * Per each delivery: resolve the destination scope from its routing, fold the delivered fact
 * through the pure reducer, remember what the fold implies (see below), CAS-write the state, then
 * dispatch the transition's effects (commit-before-dispatch, unchanged law). A CAS miss re-reads
 * and re-handles.
 *
 * <p>Both consumers fold; neither runs a tool (approval-lifecycle spec §5).
 *
 * <p>Every fold here is published on the harness's one fact stream (agentic-o11y spec §3) — an
 * applied one with its transition, a dropped one as {@code ignored} — through the same door {@link
 * DefaultAgent} uses. Before that stream existed a delivered fold narrated nothing at all, so the
 * configured {@code HarnessObserver} saw only the synchronous half of a scope's life; it now sees
 * both.
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
 * org.jwcarman.nessy.spi.Memory#remember(org.jwcarman.nessy.spi.Remembrance)} BEFORE the state CAS
 * ever runs. A throwing {@code remember} aborts the attempt before that write; Continuum's own
 * {@link ContinuumClient#deliverResults} catches the exception and releases the delivery for a
 * later retry (its own backoff, not a Substrate redrive). A successful {@code remember} that is
 * later followed by a lost CAS just re-remembers the same keys on retry, which converges by the
 * SPI's own idempotence law. This is what makes ANY {@link org.jwcarman.nessy.spi.Memory} —
 * substrate-backed or a genuinely foreign store — a first-class citizen here: this worker no longer
 * inspects what kind of {@code Memory} a scope is wired with.
 */
final class DeliveryWorker<O> implements ComputationPump {

  private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
  private static final String STATE_KIND = "state";

  /**
   * The approval kind's lease (approval-lifecycle spec §5): short, because this consumer only folds
   * — the answer is one {@code Substrate} write, never a tool run, so nothing slow is in flight for
   * a lease to outlast. The lease pays for delivering a message, never for doing the work.
   */
  private static final Lease APPROVAL_LEASE = Lease.ofSeconds(30);

  private static final Backoff APPROVAL_BACKOFF = Backoff.ofSeconds(5);

  /** The approval sweep's own per-pass cap — small, since a human-gated backlog is never large. */
  private static final BatchSize APPROVAL_BATCH_SIZE = BatchSize.of(100);

  /**
   * The tool kind's lease (continuum-adoption spec §11.2, ruled): short, because this consumer only
   * folds — it never runs a tool inline, so there is no slow in-flight work a lease must outlast.
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
   * The approval kind's Continuum client (continuum-adoption spec §3, §7) — {@code null} for a
   * worker built by a pre-migration test constructor that never wires approvals; {@link
   * #drainApprovals} and its callers no-op when absent rather than assuming every worker in this
   * module's test suite has been repointed at Continuum yet.
   */
  private final ContinuumClient<Approval, ApprovalRouting> approvalClient;

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
    this(store, mapper, harness, resolver, Runnable::run, null, null);
  }

  /**
   * The production shape (continuum-adoption spec §3, §7): a worker wired for the approval and tool
   * kinds' Continuum clients and the {@link Executor} {@link #nudge()}'s post-completion drain
   * passes submit to — {@link Harness#of} hands in the same shared {@link ComputationScheduler} it
   * registers this worker's six pumps with.
   *
   * @param nudgeExecutor where {@link #nudge()} submits its approval/tool drain passes
   */
  DeliveryWorker(
      Substrate store,
      ObjectMapper mapper,
      Harness<O> harness,
      AgentResolver resolver,
      Executor nudgeExecutor,
      ContinuumClient<Approval, ApprovalRouting> approvalClient,
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
   * The approval kind's own consumer (approval-lifecycle spec §5): read the delivery's {@link
   * Approval} and routing, fold {@code ApprovalAnswered} into the scope, commit, return. A {@code
   * Failure} or {@code Expired} folds as a denial so the model reads the reason in-band. Continuum
   * acknowledges the delivery once this consumer returns normally; a thrown exception releases the
   * claim, backed off by {@link #APPROVAL_BACKOFF}.
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
          Routing routing = delivery.continuation().routing();
          ComputationId id = ComputationId.of(delivery.computationId().value().toString());
          Approval answer =
              switch (delivery.outcome()) {
                case TypedOutcome.Success<Approval> success -> success.value();
                case TypedOutcome.Failure<Approval> failure ->
                    new Approval.Denied(failure.message(), Optional.of("continuum:failure"));
                case TypedOutcome.Expired<Approval> expired ->
                    new Approval.Denied(
                        expired.kind() + ": " + expired.message(),
                        Optional.of("continuum:expired"));
              };
          fold(
              routing,
              new AgentEvent.ApprovalAnswered(routing.call(), Optional.of(id), answer),
              id);
        });
  }

  /**
   * The tool kind's own consumer (continuum-adoption spec §3, §7, §11.2): like the approval
   * consumer, this one only folds an already-produced {@link ToolResult} (or a failure/expiry) into
   * the scope, which is why its lease ({@link #TOOL_LEASE}, 30s) can be short.
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
    ComputationId id = ComputationId.of(delivery.computationId().value().toString());
    fold(
        routing,
        new AgentEvent.ToolFinished(
            routing.call(), Optional.of(id), toToolOutcome(delivery.outcome())),
        id);
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
   * One fold-advance for either kind: read state, reduce, remember, CAS-write, dispatch.
   *
   * <p>An ignored transition is DROPPED — logged at WARN and acknowledged, never released for
   * redelivery (James's 2026-08-25 ruling, approval-lifecycle spec §4). A delivery whose scope is
   * not in the status that awaits it is a permanent failure, not a race worth retrying: {@code
   * ComputationApprovalContext#defer()} folds {@code AwaitingApproval} and commits BEFORE it hands
   * back the id, so no answer can outrun its own park; and a {@code Running} call names no
   * computation at all, so a delivered id reaching one is by definition an id the scope knows
   * nothing of (spec §3). Nothing is lost by dropping that last case, because the window does not
   * open in practice (spec §4). What remains is an orphan or a duplicate, and no amount of backoff
   * makes it fold.
   */
  private void fold(Routing routing, AgentEvent event, ComputationId delivered) {
    AgentType type = AgentType.of(routing.agentType());
    AgentId id = AgentId.of(routing.agentId());
    while (true) {
      State state = warnIfNoStoredState(id, readState(id));
      Transition transition = state.phase().handle(event);
      if (transition.isIgnored()) {
        warnDropped(id, routing, delivered, state.phase());
        // The drop is a fact about the scope too (agentic-o11y spec §3): it goes out on the same
        // stream an applied fold does, so a subscriber sees the delivery that changed nothing.
        harness.facts().ignored(id, event);
        return; // dropped — acknowledged, never redelivered
      }
      if (event instanceof AgentEvent.ToolFinished(var call, var _, var outcome)) {
        ToolFoldRemembrance.remember(
            harness.memoryFor(id), type, id, state.phase(), call, outcome, transition);
      }
      // A denial finishes the call with an error result the model reads, so it is remembered the
      // same way a failed tool is — and it may be the call that commits the whole turn.
      if (event
          instanceof
          AgentEvent.ApprovalAnswered(var call, var _, Approval.Denied(var reason, var _))) {
        ToolFoldRemembrance.rememberDenial(
            harness.memoryFor(id), type, id, state.phase(), call, reason, transition);
      }
      try {
        states.write(id.value(), transition.next(), state.version());
      } catch (ConflictException _) {
        countStaleRetry(id, type);
        continue; // lost the race — re-read and re-handle
      }
      // Published only once the write succeeded: the stream carries the fold's OUTPUT, and until
      // the CAS lands nothing has happened to the scope (agentic-o11y spec §3).
      harness.facts().applied(id, event, transition);
      dispatchEffects(type, id, transition.next(), transition.effects());
      return;
    }
  }

  /**
   * One stale-retry counted, with no guard of its own for the reason {@code DefaultAgent} states on
   * its own arm of this loop (fix round 1): {@link Observations#staleRetry} never throws. A CAS
   * miss is an ordinary condition this loop converges past, and an escaping exception would turn it
   * into a failed delivery that Continuum then redelivers forever.
   */
  private void countStaleRetry(AgentId id, AgentType type) {
    harness.observations().staleRetry(id, type);
  }

  /**
   * Names the dropped delivery loudly enough to chase: which scope, which call, which computation,
   * and the status the phase was actually in — the four coordinates that distinguish an orphan from
   * a duplicate when someone reads the log afterwards.
   */
  private static void warnDropped(
      AgentId id, Routing routing, ComputationId delivered, Phase phase) {
    log.warn(
        "dropping a delivery this scope is not awaiting: agent={} call={} computation={} status={}",
        id.value(),
        routing.call().id(),
        delivered.value(),
        statusOf(phase, routing.call().id()));
  }

  /** How the phase describes this call right now, for {@link #warnDropped}'s message. */
  private static String statusOf(Phase phase, String callId) {
    if (!(phase instanceof Phase.AwaitingTools awaiting)) {
      return phase.getClass().getSimpleName();
    }
    CallStatus status = awaiting.calls().get(callId);
    return status == null ? "no such call" : status.getClass().getSimpleName();
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
   * {@code phase} is the transition's committed {@code next()} — a call effect here reads its
   * {@code ModelResponseId} from {@link org.jwcarman.nessy.agent.Phase.AwaitingTools}, the only
   * phase that ever carries one.
   */
  private void dispatchEffects(AgentType type, AgentId id, Phase phase, List<Effect> effects) {
    for (Effect effect : effects) {
      switch (effect) {
        case Effect.CallModel _ ->
            harness.modelExecutorFor(id).callModel(event -> binder.deliver(type, id, event));
        case Effect.SeekApproval(var call) ->
            harness
                .toolExecutorFor(id)
                .seekApproval(call, responseIdOf(phase), event -> binder.deliver(type, id, event));
        case Effect.RunTool(var call) ->
            harness
                .toolExecutorFor(id)
                .runTool(call, responseIdOf(phase), event -> binder.deliver(type, id, event));
      }
    }
  }

  private static ModelResponseId responseIdOf(Phase phase) {
    if (phase instanceof Phase.AwaitingTools awaiting) {
      return awaiting.responseId();
    }
    throw new IllegalStateException("a call effect was dispatched outside AwaitingTools: " + phase);
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
