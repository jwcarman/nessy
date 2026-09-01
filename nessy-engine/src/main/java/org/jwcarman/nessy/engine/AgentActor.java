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
package org.jwcarman.nessy.engine;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.apache.pekko.actor.CoordinatedShutdown;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.DispatcherSelector;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.state.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.state.javadsl.DurableStateBehavior;
import org.apache.pekko.persistence.typed.state.javadsl.Effect;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.backlog.BacklogItem;

/**
 * One agent instance: what it has been told, and whether it is busy.
 *
 * <p><b>It does not drive anything.</b> Running a turn is the turn actor's job; this actor persists
 * facts, grooms the backlog, and starts at most ONE turn. That division is why its document is
 * rewritten only when the backlog changes — a turn making eight tool calls never touches it.
 *
 * <p><b>The observation boundary ends here.</b> An observation arrives encoded, is decoded into
 * typed state, and is rendered to a {@code UserMessage} before a turn is asked for — so the turn
 * and everything under it is free of the application's vocabulary.
 *
 * <p><b>At most one turn, ever.</b> {@code turnId} being set IS the fact that one is running, so
 * state and actor tree cannot disagree about it. A second observation arriving mid-turn joins the
 * backlog, which is what the coalescer is for.
 *
 * @param <O> the observation type
 */
public final class AgentActor<O> extends DurableStateBehavior<NessyMessage, AgentState<O>> {

  /** Where turn actors run. Shipped in this module's own reference.conf. */
  private static final String TURN_DISPATCHER = "nessy.turn-dispatcher";

  private final ActorContext<NessyMessage> context;
  private final AgentType agentType;
  private final AgentId agentId;
  private final Codec<O> codec;
  private final BacklogCoalescer<O> coalescer;
  private final ObservationRenderer<O> renderer;
  private final Turns turns;
  private final Clock clock;
  private final Traces traces;

  /** The turn in flight, if any. Rebuilt on recovery, never persisted: it is an address. */
  private org.apache.pekko.actor.typed.ActorRef<TurnActor.Command> turn;

  private final ActorRef<ClusterSharding.ShardCommand> shard;

  private AgentActor(
      ActorContext<NessyMessage> context,
      Dependencies<O> deps,
      AgentId agentId,
      ActorRef<ClusterSharding.ShardCommand> shard) {
    super(PersistenceId.of(deps.agentType().name(), agentId.value()));
    this.context = context;
    this.agentType = deps.agentType();
    this.agentId = agentId;
    this.codec = deps.codec();
    this.coalescer = deps.coalescer();
    this.renderer = deps.renderer();
    this.turns = deps.turns();
    this.clock = deps.clock();
    this.traces = deps.traces();
    this.shard = shard;
  }

  /**
   * What one KIND of agent needs. Infrastructure is absent by design: {@link Turns} closes over the
   * model, the tools, and memory, so adding a dependency to a turn never changes this record.
   */
  public record Dependencies<O>(
      AgentType agentType,
      Codec<O> codec,
      BacklogCoalescer<O> coalescer,
      ObservationRenderer<O> renderer,
      Turns turns,
      Clock clock,
      Traces traces) {}

  public static <O> Behavior<NessyMessage> create(
      Dependencies<O> deps, AgentId agentId, ActorRef<ClusterSharding.ShardCommand> shard) {
    Objects.requireNonNull(deps, "deps must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(shard, "shard must not be null");
    return Behaviors.setup(context -> new AgentActor<>(context, deps, agentId, shard));
  }

  @Override
  public AgentState<O> emptyState() {
    return AgentState.idle(agentType);
  }

  /**
   * Every message handled inside a CONSUMER span parented to whatever the sender carried.
   *
   * <p>CONSUMER because the mailbox is a queue: paired with the send, the gap between them is queue
   * latency, which is exactly the thing an actor system makes easy to have and hard to see.
   *
   * <p><b>This is what makes the trace a tree.</b> Everything the handler does — including handing
   * work to the blocking executor, which the starter wraps to carry context across — happens inside
   * this scope, so a model or tool span opened out there finds this span as its parent instead of
   * becoming a root of its own.
   */
  /**
   * Every message handled inside a CONSUMER span parented to whatever the sender carried.
   *
   * <p><b>Wrapped HERE and not by a {@code BehaviorInterceptor}</b>, which was tried and reverted.
   * An interceptor's {@code aroundReceive} does not enclose a {@link DurableStateBehavior}'s
   * command handler — the handler runs outside that scope — so a capture inside a handler came back
   * empty and the whole tree collapsed to two spans. Measured, not reasoned about.
   */
  @Override
  public CommandHandler<NessyMessage, AgentState<O>> commandHandler() {
    CommandHandler<NessyMessage, AgentState<O>> handler = traced();
    return (state, message) ->
        traces.inSpan(
            "agent receive " + message.getClass().getSimpleName(),
            message.headers(),
            () -> {
              describe(message);
              return handler.apply(state, message);
            });
  }

  /** What this actor knows about itself, written onto the receive span the interceptor opened. */
  void describe(NessyMessage message) {
    traces.tag("messaging.system", "pekko");
    traces.detail("nessy.agent.id", agentId.value());
    traces.tag("nessy.agent.type", agentType.name());
    traces.tag("nessy.message.type", message.getClass().getSimpleName());
    // Locally this renders "pekko://nessy"; clustered it becomes "pekko://nessy@host:port", so the
    // same attribute answers "which box" the day this is multi-node.
    traces.tag("nessy.node.address", context.getSystem().address().toString());
  }

  private CommandHandler<NessyMessage, AgentState<O>> traced() {
    return newCommandHandlerBuilder()
        .forAnyState()
        .onCommand(NessyMessage.Observe.class, this::onObserve)
        .onCommand(NessyMessage.TurnFinished.class, this::onTurnFinished)
        .onCommand(NessyMessage.Expired.class, this::onExpired)
        .onCommand(NessyMessage.Wake.class, this::onWake)
        .onCommand(NessyMessage.AnswerToolCall.class, this::onAnswerToolCall)
        .onCommand(NessyMessage.AnswerApproval.class, this::onAnswerApproval)
        .onCommand(NessyMessage.Stop.class, this::onStop)
        .onCommand(NessyMessage.Inspect.class, this::onInspect)
        .build();
  }

  /**
   * Decode, then let the coalescer decide what the arrival does to what is already waiting — keep
   * it, drop it, supersede something older, merge. This is the ONE place an observation can be
   * refused; a renderer deliberately cannot.
   */
  private Effect<AgentState<O>> onObserve(AgentState<O> state, NessyMessage.Observe message) {
    BacklogItem<O> arrival =
        new BacklogItem<>(Identifiers.next(), codec.decode(message.observation()), clock.instant());
    // Captured inside the handler: the nudge below runs from thenRun, after the scope has closed,
    // and a Wake sent with empty headers starts a NEW trace — which is why an entire round used to
    // hang under "agent receive Wake" instead of under the observation that caused it.
    Map<String, String> here = traces.capture(agentType.name(), agentId.value(), "Wake");
    return Effect()
        .persist(state.ingesting(coalescer, arrival))
        .thenRun(persisted -> nudge(persisted, here));
  }

  /** The turn is over. Its observation is done with, and the next one may start. */
  private Effect<AgentState<O>> onTurnFinished(
      AgentState<O> state, NessyMessage.TurnFinished message) {
    if (!message.turnId().equals(state.turnId())) {
      // A turn that is not the one we are running has nothing to report. Ignoring rather than
      // failing: at-least-once delivery means a late duplicate is expected, not exceptional.
      return Effect().none();
    }
    turn = null;
    Map<String, String> here = traces.capture(agentType.name(), agentId.value(), "Wake");
    return Effect().persist(state.finished()).thenRun(persisted -> nudge(persisted, here));
  }

  /**
   * A parked call's deadline passed.
   *
   * <p>Arrives at this agent's LOGICAL address, so reaching a passivated agent reactivates it —
   * which is what lets a three-day approval stop depending on anything staying in memory.
   *
   * <p>No turn, or a call this turn does not hold, is a no-op rather than an error: the sweep is
   * at-least-once, and a call may have settled a moment before its reminder fired.
   */
  private Effect<AgentState<O>> onExpired(AgentState<O> state, NessyMessage.Expired message) {
    if (turn != null) {
      turn.tell(new TurnActor.RelayDeadline(message.callId()));
    }
    return Effect().none();
  }

  /**
   * Start a turn if there is work and none is running.
   *
   * <p>Taking is its own durable write, separate from ingesting: the head has to be OUT of the
   * coalescer's reach before anything runs on it, and that fact has to survive a crash.
   */
  private Effect<AgentState<O>> onWake(AgentState<O> state, NessyMessage.Wake message) {
    if (state.busy()) {
      // A turn is claimed. If nothing is running it, this process came back from a crash: the
      // claim outlived the actor that made it. Without this the agent is stranded for good -- it
      // will never start a turn (it thinks one is running) and never finish one (nothing is).
      if (context.getChild(turnName(state.turnId())).isEmpty()) {
        // A respawn after a crash: this Wake is the message that revived the turn, so the turn's
        // work hangs off the wake rather than off the observation that started it days ago.
        startTurn(state, traces.capture(agentType.name(), agentId.value(), "Begin"));
      }
      return Effect().none();
    }
    if (!state.hasWork()) {
      return Effect().none();
    }
    String turnId = Identifiers.next();
    // Captured HERE, inside the receive span, and closed over. By the time thenRun fires the
    // scope is gone and a capture would come back empty.
    Map<String, String> here = traces.capture(agentType.name(), agentId.value(), "Begin");
    return Effect().persist(state.taking(turnId)).thenRun(taken -> startTurn(taken, here));
  }

  private Effect<AgentState<O>> onInspect(AgentState<O> state, NessyMessage.Inspect message) {
    message.replyTo().tell(state);
    return Effect().none();
  }

  private void startTurn(AgentState<O> taken, Map<String, String> carried) {
    turn =
        context.spawn(
            turns.turn(
                agentId,
                taken.turnId(),
                renderer.render(taken.inFlight().observation()),
                context.getSelf(),
                carried),
            turnName(taken.turnId()),
            // Not the default dispatcher: a turn writes claims and reads memory inside its own
            // command handlers, and an application's Memory is arbitrary code. Blocking here must
            // not starve sharding, gossip and narration, so turns get a pool of their own.
            DispatcherSelector.fromConfig(TURN_DISPATCHER));
  }

  /**
   * An answer from outside, for a call this agent's turn parked.
   *
   * <p>Acked only once it has actually reached the turn, because whoever is answering — an HTTP
   * handler, most likely — must not report success for something that went nowhere. A call whose
   * turn is gone is answered honestly rather than silently dropped: it has already settled, or its
   * deferral expired, and either way the answer is too late.
   */
  private Effect<AgentState<O>> onAnswerToolCall(
      AgentState<O> state, NessyMessage.AnswerToolCall message) {
    if (turn == null) {
      message.replyTo().tell(new NessyMessage.Ack(false, "no turn is in flight"));
      return Effect().none();
    }
    turn.tell(new TurnActor.RelayResult(message.callId(), message.result()));
    message.replyTo().tell(new NessyMessage.Ack(true, "delivered"));
    return Effect().none();
  }

  private Effect<AgentState<O>> onAnswerApproval(
      AgentState<O> state, NessyMessage.AnswerApproval message) {
    if (turn == null) {
      message.replyTo().tell(new NessyMessage.Ack(false, "no turn is in flight"));
      return Effect().none();
    }
    turn.tell(new TurnActor.RelayApproval(message.callId(), message.result()));
    message.replyTo().tell(new NessyMessage.Ack(true, "delivered"));
    return Effect().none();
  }

  /**
   * The shard has confirmed the passivation this agent asked for — which does NOT mean the agent is
   * still idle.
   *
   * <p>An observation can be delivered in the window between asking and being told, so this can
   * arrive with a turn already claimed or a backlog already taken. Stopping then kills the turn
   * actor, which is a CHILD; the model call it left running completes into a dead ref and nobody is
   * left to end the turn. That is the hang: the claim outlives every actor that could honour it.
   *
   * <p><b>Refusing to stop is not the fix</b>, however tempting. Measured against Pekko: an entity
   * that ignores its stop message is stranded for good — the shard buffers every later message and
   * delivers none of them, and the region logs {@code entities ... not stopped}. Stopping is
   * mandatory.
   *
   * <p>So it stops, but not silently: it first posts a {@link NessyMessage.Wake} to its OWN entity
   * id through the shard, the one address that outlives this incarnation. The shard holds it until
   * this actor is gone and hands it to the successor, whose {@link #onWake} finds a claimed turn
   * with no actor running it and starts one — the same recovery a crash would get. Without it, that
   * recovery exists but nothing ever triggers it.
   */
  private Effect<AgentState<O>> onStop(AgentState<O> state, NessyMessage.Stop message) {
    if ((state.busy() || state.hasWork()) && !shuttingDown()) {
      ClusterSharding.get(context.getSystem())
          .entityRefFor(EntityTypeKey.create(NessyMessage.class, agentType.name()), agentId.value())
          .tell(new NessyMessage.Wake(message.headers()));
    }
    return Effect().none().thenStop();
  }

  /**
   * Whether this node is on its way out, as opposed to merely unloading an idle agent.
   *
   * <p>Pekko sends the SAME stop message for both, so without this the revival above fires during
   * shutdown and hand-off: the shard re-creates the agent it is trying to drain, the region never
   * finishes handing off, and the process hangs on the way out instead of on the way in. Measured —
   * two tests wedged for the full ten-second hand-off timeout.
   *
   * <p>Nothing is lost by staying quiet here. The turn stays claimed in durable state, and whatever
   * picks this agent up next — this node after a restart, or another node taking the shard — finds
   * the claim with no actor running it and starts one, which is the same recovery a crash gets.
   *
   * <p><b>Reminders do not replace this, and it was tried.</b> A reminder is armed when a call
   * PARKS — an approval waiting on a person, a tool waiting on the world — so a parked call now
   * survives this actor dying. A turn killed during a MODEL CALL parked nothing, so nothing was
   * armed, and deleting the revival above brought the original hang straight back: measured, turn
   * two silent for over a minute while the provider answered a fresh request in half a second.
   *
   * <p>What would retire this is a deadline on the TURN rather than on its parked calls — claim
   * expiry, per the 2026-08-28 actor-composition spec §8a. Until that exists, this stays.
   */
  private boolean shuttingDown() {
    return CoordinatedShutdown.get(context.getSystem()).getShutdownReason().isPresent();
  }

  /**
   * Asks to be unloaded, but ONLY when idle.
   *
   * <p>This is the rule the phase actors' deadlines depend on. A turn actor is a child, so
   * passivating mid-turn would kill it — and with it the timers holding an approval's term and a
   * deferral's expiry. Nothing else would fire them, and a parked call would outlive its deadline
   * silently. Staying resident while a turn is in flight is what makes those timers sufficient and
   * a sweeper unnecessary.
   */
  private void passivateIfIdle(AgentState<O> state) {
    if (!state.busy() && !state.hasWork()) {
      shard.tell(new ClusterSharding.Passivate<>(context.getSelf()));
    }
  }

  private static String turnName(String turnId) {
    return "turn-" + turnId;
  }

  /** Ask ourselves whether there is now work to start; {@link #onWake} decides. */
  private void nudge(AgentState<O> state, Map<String, String> carried) {
    context.getSelf().tell(new NessyMessage.Wake(carried));
    passivateIfIdle(state);
  }
}
