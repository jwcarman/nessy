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
package org.jwcarman.nessy.agent.durable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.agent.AgentBinder;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentResolver;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.Binding;
import org.jwcarman.nessy.agent.DurableOutcomes;
import org.jwcarman.nessy.agent.Effect;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.ResolvingAgentBinder;
import org.jwcarman.nessy.agent.ScopeRedrive;
import org.jwcarman.nessy.agent.State;
import org.jwcarman.nessy.agent.ToolError;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.codec.MessageCodec;
import org.jwcarman.nessy.agent.codec.StateCodec;
import org.jwcarman.nessy.agent.durable.OutcomeCodec.DeliveryDocument;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.Outcome;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.spi.substrate.Substrate.Op.AppendEntry;
import org.jwcarman.nessy.spi.substrate.Substrate.Op.WriteDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivery is fold-advance (durable-deliveries spec §5): the one consumer of outbox deliveries. Per
 * each delivery: read and decode it, resolve the destination scope from its continuation, reconcile
 * the pending call with its outcome through the pure reducer, commit one substrate batch — journal
 * appends for whatever the transition commits, the CAS state write, and the delivery's own removal
 * — then dispatch the transition's effects (commit-before-dispatch, unchanged law). A CAS miss
 * re-reads and re-handles; {@link org.jwcarman.nessy.agent.Transition#isIgnored()} means the batch
 * is just the delivery removal.
 *
 * <p>An approval's {@code Allow} decision is not a completion — the tool has not run yet, so there
 * is no {@code ToolFinished} for the reducer to fold. That case reuses {@link ScopeRedrive}'s
 * existing, tested mechanism exactly as it always worked: remove the delivery, then unconditionally
 * re-fire the scope's still-outstanding {@code ExecuteTool} effect.
 *
 * <p>One heartbeat thread per host, started by the host and stopped on {@link #close()}; {@link
 * #nudge()} runs an immediate, synchronous drain after every completion — the heartbeat is the
 * recovery net, never the happy-path latency (spec §5).
 *
 * <p>The journal writes go straight through the {@code Substrate} the {@code memory} recipe defines
 * (kind {@code memory}, keyed by agent id, {@link MessageCodec}-encoded) so they land in the SAME
 * batch as the state write and the delivery's own removal — this is what "one substrate batch"
 * (spec §5) means. It follows that a scope's {@link org.jwcarman.nessy.spi.Memory} must actually be
 * the substrate-backed {@link org.jwcarman.nessy.agent.memory.SubstrateMemory} for delivery to
 * reach it; a non-substrate {@code Memory} (e.g. an in-process test double) never sees these
 * appends, because there is no substrate underneath it to batch into.
 */
public final class DeliveryWorker<O> implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
  private static final String OUTBOX_KIND = "outbox";
  private static final String STATE_KIND = "state";
  private static final String MEMORY_KIND = "memory";
  private static final int SCAN_LIMIT = 1000;
  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);

  private final Substrate store;
  private final OutcomeCodec codec;
  private final StateCodec stateCodec;
  private final MessageCodec messageCodec;
  private final Harness<O> harness;
  private final AgentBinder binder;
  private final ScopeRedrive scopeRedrive;
  private final ObjectMapper mapper;
  private final Thread heartbeat;
  private volatile boolean closed;

  public DeliveryWorker(
      Substrate store, ObjectMapper mapper, Harness<O> harness, AgentResolver resolver) {
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
    this.stateCodec = new StateCodec(mapper);
    this.messageCodec = new MessageCodec(mapper);
    this.harness = Objects.requireNonNull(harness, "harness must not be null");
    Objects.requireNonNull(resolver, "resolver must not be null");
    this.binder = new ResolvingAgentBinder(resolver);
    this.scopeRedrive = new ScopeRedrive(resolver, mapper);
    Objects.requireNonNull(pollInterval, "pollInterval must not be null");
    this.heartbeat = new Thread(() -> heartbeatLoop(pollInterval), "nessy-delivery");
    this.heartbeat.setDaemon(true);
  }

  public void start() {
    heartbeat.start();
  }

  /**
   * An immediate, synchronous drain — the happy path after every completion. Never throws: a
   * completing desk calls this after its own commit, and a nudge failure must not surface as if the
   * desk's own {@code complete()} had failed.
   */
  public void nudge() {
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

  /**
   * One bad delivery — an undecodable continuation, an unknown outcome vocabulary, a resolver
   * failure — must not block every other pending delivery behind it (no head-of-line blocking), so
   * each is guarded individually and logged rather than left to abort the whole scan.
   */
  private void drainOnce() {
    for (String key : store.keys(OUTBOX_KIND, SCAN_LIMIT)) {
      try {
        deliverOne(key);
      } catch (RuntimeException e) {
        log.warn("delivery {} could not be processed; skipped this sweep", key, e);
      }
    }
  }

  private void deliverOne(String key) {
    Optional<Substrate.Document> doc = store.read(OUTBOX_KIND, key);
    if (doc.isEmpty()) {
      return; // already delivered by another drain — deliveries are pending-only (spec §4)
    }
    DeliveryDocument delivery =
        codec.deliveryDocument(new String(doc.get().payload(), StandardCharsets.UTF_8));
    ScopeRouting.Routing routing = ScopeRouting.decode(mapper, delivery.destination());
    AgentType type = AgentType.of(routing.agentType());
    AgentId id = AgentId.of(routing.agentId());
    Optional<ToolOutcome> toolOutcome = toToolOutcome(delivery.outcome());
    if (toolOutcome.isEmpty()) {
      deliverGrant(key, doc.get().version(), delivery);
      return;
    }
    deliverCompletion(type, id, routing.call().id(), toolOutcome.get(), key);
  }

  /**
   * An approval grant is not a fold-advance: the batch is just the delivery removal, and the
   * outstanding call is re-fired afterward (commit-before-dispatch) via {@link ScopeRedrive}'s
   * existing mechanism. Unlike {@link #deliverCompletion}, this arm is at-most-once, not
   * exactly-once (spec §5a): the delivery is removed before the redispatch fires, so a crash
   * between the two loses the grant rather than replaying it — Task 3 closes this gap; do not read
   * this as parity with the completion arm's CAS-batched guarantee.
   */
  private void deliverGrant(String key, long version, DeliveryDocument delivery) {
    try {
      store.delete(OUTBOX_KIND, key, version);
    } catch (ConflictException _) {
      return; // another drain already delivered this delivery
    }
    scopeRedrive.completed(delivery.destination(), delivery.outcome());
  }

  private void deliverCompletion(
      AgentType type, AgentId id, String callId, ToolOutcome outcome, String deliveryKey) {
    requirePlainSubstrateMemory(id);
    while (true) {
      Optional<Substrate.Document> deliveryDoc = store.read(OUTBOX_KIND, deliveryKey);
      if (deliveryDoc.isEmpty()) {
        return; // another drain already delivered this delivery
      }
      Optional<Substrate.Document> stateDoc = store.read(STATE_KIND, id.value());
      State state =
          stateDoc
              .map(
                  d ->
                      new State(
                          stateCodec.phase(new String(d.payload(), StandardCharsets.UTF_8)),
                          d.version()))
              .orElseGet(State::initial);
      var call = routingCall(state, callId);
      var event = new AgentEvent.ToolFinished(call, outcome);
      var transition = state.phase().handle(event);
      List<Substrate.Op> ops = new ArrayList<>();
      if (!transition.isIgnored()) {
        long seq = currentMemoryHead(id);
        for (Message message : transition.commit()) {
          seq++;
          ops.add(
              new AppendEntry(
                  MEMORY_KIND,
                  id.value(),
                  seq,
                  messageCodec.toJson(message).getBytes(StandardCharsets.UTF_8)));
        }
        byte[] statePayload = stateCodec.toJson(transition.next()).getBytes(StandardCharsets.UTF_8);
        ops.add(new WriteDocument(STATE_KIND, id.value(), statePayload, state.version()));
      }
      ops.add(
          new Substrate.Op.DeleteDocument(OUTBOX_KIND, deliveryKey, deliveryDoc.get().version()));
      try {
        store.batch(ops);
      } catch (ConflictException _) {
        continue; // lost the race — re-read state (or find the delivery already gone) and retry
      }
      if (!transition.isIgnored()) {
        dispatchEffects(type, id, transition.effects());
      }
      return;
    }
  }

  /**
   * The loud guard F3 asks for: the worker's journal appends bypass {@link
   * org.jwcarman.nessy.spi.Memory} entirely and write {@link MessageCodec}-encoded bytes straight
   * into {@code store} (this class's javadoc explains why — one atomic batch). A scope wired with
   * anything else — a different substrate, a transformed {@link
   * org.jwcarman.nessy.spi.substrate.Codec}, a non-substrate {@code Memory} test double — would
   * silently diverge from what {@link SubstrateMemory#recall()} decodes, or never see the append at
   * all. Failing loudly here, before any write, is deliberately narrower than fixing the seam
   * itself: a {@code Memory} that contributes its own batch ops is parked for James, not built
   * here.
   */
  private void requirePlainSubstrateMemory(AgentId id) {
    var memory = harness.bind(id).memory();
    if (!(memory instanceof SubstrateMemory substrateMemory)
        || !substrateMemory.writesPlainlyTo(store)) {
      throw new IllegalStateException(
          "scope "
              + id.value()
              + " is wired with a Memory the delivery worker cannot batch into — its journal"
              + " appends must be a plain SubstrateMemory over this worker's own substrate (see"
              + " DeliveryWorker's class javadoc); a custom codec or a non-substrate Memory here"
              + " would silently lose or corrupt this scope's completions");
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

  private long currentMemoryHead(AgentId id) {
    List<Substrate.Entry> entries = store.entries(MEMORY_KIND, id.value(), 1);
    return entries.isEmpty() ? 0L : entries.getLast().seq();
  }

  private void dispatchEffects(AgentType type, AgentId id, List<Effect> effects) {
    Binding<O> binding = harness.bind(id);
    for (Effect effect : effects) {
      switch (effect) {
        case Effect.CallModel _ ->
            harness.modelExecutor(binding).callModel(event -> binder.deliver(type, id, event));
        case Effect.ExecuteTool(var call) ->
            harness
                .toolExecutor(binding)
                .executeTool(call, event -> binder.deliver(type, id, event));
      }
    }
  }

  /**
   * {@code Success(Decision.Allow)} is empty — a grant, not a completion; every other outcome
   * (including a denial, whose reason becomes the tool's in-band failure) maps to a {@link
   * ToolOutcome}, mirroring {@link DurableOutcomes#toToolOutcome(Outcome)}'s own arms for the
   * genuinely-durable-tool payloads — inlined here (rather than delegated with a fallback default)
   * so a future {@link Outcome} variant fails this switch at compile time, the same exhaustiveness
   * discipline {@link DurableDecisions#toAdjudication} already holds to.
   */
  private static Optional<ToolOutcome> toToolOutcome(Outcome outcome) {
    return switch (outcome) {
      case Outcome.Success(Decision.Allow()) -> Optional.empty();
      case Outcome.Success(Decision.Deny(String reason)) ->
          Optional.of(new ToolOutcome.Failed(new ToolError(reason)));
      case Outcome.Success(Object value) when value instanceof ToolResult result ->
          Optional.of(new ToolOutcome.Returned(result));
      case Outcome.Success(Object value) ->
          Optional.of(
              new ToolOutcome.Failed(
                  new ToolError("unexpected durable payload: " + value.getClass().getName())));
      case Outcome.Failure(String message) ->
          Optional.of(new ToolOutcome.Failed(new ToolError(message)));
      case Outcome.Cancelled(String reason) ->
          Optional.of(new ToolOutcome.Failed(new ToolError("cancelled: " + reason)));
    };
  }
}
