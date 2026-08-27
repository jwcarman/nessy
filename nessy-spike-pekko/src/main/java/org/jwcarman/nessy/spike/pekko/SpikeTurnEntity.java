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
package org.jwcarman.nessy.spike.pekko;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityContext;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.state.RecoveryCompleted;
import org.apache.pekko.persistence.typed.state.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.state.javadsl.DurableStateBehavior;
import org.apache.pekko.persistence.typed.state.javadsl.Effect;
import org.apache.pekko.persistence.typed.state.javadsl.SignalHandler;

/**
 * THROWAWAY SPIKE. One agent, as a Pekko cluster-sharded durable-state entity.
 *
 * <p>Read {@link #commandHandler()} downwards and the whole turn is here:
 *
 * <pre>
 *   Observe            Idle          -> CallingModel   ... and call the model
 *   ModelReplied       CallingModel  -> WorkingTools   ... and run whatever needs no approval
 *                                    -> Idle           ... if the model just talked
 *   AnswerApproval     WorkingTools  -> WorkingTools   ... and run the call that was parked
 *   ToolFinished       WorkingTools  -> WorkingTools   ... or back to CallingModel when all settled
 * </pre>
 *
 * <p>The three properties the hand-rolled design was chasing all fall out of the shape rather than
 * being built:
 *
 * <ul>
 *   <li><b>No lease.</b> Sharding guarantees at most one instance of this entity id cluster-wide,
 *       and an actor processes one message at a time. There is no second writer to exclude, so
 *       there is nothing to lease.
 *   <li><b>No compare-and-swap in our code.</b> {@code Effect().persist(next)} is the only write,
 *       and the store enforces the revision itself.
 *   <li><b>No callback address.</b> A parked approval is answered by sending {@link AnswerApproval}
 *       to this agent's entity id. The id is the address, so nothing has to be recorded anywhere
 *       for a human to find their way back.
 * </ul>
 *
 * <p>What is NOT free is {@link #resume}: after a rehydration Pekko hands us the state back and
 * stops. Deciding that a {@code CallingModel} still owes a model call is our rule, and we still
 * have to write it. That is the hand-rolled design's {@code outstanding()}, unchanged in substance.
 */
public final class SpikeTurnEntity
    extends DurableStateBehavior<SpikeTurnEntity.Command, SpikeTurnState> {

  public static final EntityTypeKey<Command> TYPE_KEY =
      EntityTypeKey.create(Command.class, "SpikeTurn");

  // ------------------------------------------------------------------------------------------
  // Commands
  // ------------------------------------------------------------------------------------------

  public sealed interface Command extends SpikeSerializable {}

  /** From the world: something happened that the agent should react to. */
  public record Observe(String text) implements Command {}

  /** From the world, out of band, possibly days later, possibly to a different JVM. */
  public record AnswerApproval(String callId, boolean approved, String reason) implements Command {}

  /** From the world: what does this agent look like right now? */
  public record Inspect(ActorRef<SpikeTurnState> replyTo) implements Command {}

  /** From ourselves: the model call we issued has come back. */
  public record ModelReplied(SpikeModelReply reply) implements Command {}

  /** From ourselves: a tool we ran has come back. */
  public record ToolFinished(String callId, String result) implements Command {}

  /**
   * From the world: let go of memory. The entity asks its shard to passivate it; the shard replies
   * with {@link #STOP}, which is the only message that actually ends the actor. Proves the park
   * costs nothing while it waits.
   */
  public record Rest() implements Command {}

  /** The shard's stop message — see {@link Rest}. */
  public record Stop() implements Command {}

  public static final Stop STOP = new Stop();

  // ------------------------------------------------------------------------------------------

  private final ActorContext<Command> context;
  private final ActorRef<ClusterSharding.ShardCommand> shard;
  private final String agentId;
  private final SpikeModel model;
  private final SpikeToolbox toolbox;

  public static Behavior<Command> create(
      EntityContext<Command> entityContext, SpikeModel model, SpikeToolbox toolbox) {
    return Behaviors.setup(
        context ->
            new SpikeTurnEntity(
                context, entityContext.getShard(), entityContext.getEntityId(), model, toolbox));
  }

  private SpikeTurnEntity(
      ActorContext<Command> context,
      ActorRef<ClusterSharding.ShardCommand> shard,
      String agentId,
      SpikeModel model,
      SpikeToolbox toolbox) {
    super(PersistenceId.of(TYPE_KEY.name(), agentId));
    this.context = context;
    this.shard = shard;
    this.agentId = agentId;
    this.model = model;
    this.toolbox = toolbox;
  }

  @Override
  public SpikeTurnState emptyState() {
    return SpikeTurnState.Idle.empty();
  }

  // ------------------------------------------------------------------------------------------
  // The turn
  // ------------------------------------------------------------------------------------------

  @Override
  public CommandHandler<Command, SpikeTurnState> commandHandler() {
    return (state, command) ->
        switch (command) {
          case Inspect inspect -> Effect().none().thenRun(() -> inspect.replyTo().tell(state));
          case Rest _ -> Effect().none().thenRun(this::askShardToPassivate);
          case Stop _ -> Effect().none().thenStop();
          case Observe observe -> onObserve(state, observe);
          case ModelReplied replied -> onModelReplied(state, replied);
          case AnswerApproval answer -> onAnswerApproval(state, answer);
          case ToolFinished finished -> onToolFinished(state, finished);
        };
  }

  /**
   * Idle plus an observation starts a turn. Anything else is busy; the spike drops rather than
   * queues.
   */
  private Effect<SpikeTurnState> onObserve(SpikeTurnState state, Observe observe) {
    if (!(state instanceof SpikeTurnState.Idle)) {
      return ignored("observation while a turn is in flight");
    }
    var next =
        new SpikeTurnState.CallingModel(
            SpikeTurnState.plus(state.transcript(), "user: " + observe.text()));
    return Effect().persist(next).thenRun(() -> callModel(next));
  }

  /** The model either finished the turn, or asked for tools. */
  private Effect<SpikeTurnState> onModelReplied(SpikeTurnState state, ModelReplied replied) {
    if (!(state instanceof SpikeTurnState.CallingModel)) {
      return ignored("model reply while not calling the model");
    }
    return switch (replied.reply()) {
      case SpikeModelReply.Said(String text) ->
          Effect()
              .persist(
                  new SpikeTurnState.Idle(
                      SpikeTurnState.plus(state.transcript(), "assistant: " + text)));
      case SpikeModelReply.AskedForTools(var requests) -> {
        var calls = requests.stream().map(toolbox::open).toList();
        var next =
            new SpikeTurnState.WorkingTools(
                SpikeTurnState.plus(
                    state.transcript(),
                    "assistant: (asked for "
                        + calls.stream().map(SpikeToolCall::tool).toList()
                        + ")"),
                calls);
        yield Effect().persist(next).thenRun(() -> dispatch(next));
      }
    };
  }

  /** The park's other end. Arrives whenever, from wherever, to whichever JVM is hosting this id. */
  private Effect<SpikeTurnState> onAnswerApproval(SpikeTurnState state, AnswerApproval answer) {
    if (!(state instanceof SpikeTurnState.WorkingTools working)) {
      return ignored("approval answer while not working tools");
    }
    var call = working.call(answer.callId());
    if (call.isEmpty() || !(call.get().phase() instanceof SpikeCallPhase.AwaitingApproval)) {
      // a duplicate answer, or one for a call that never parked: idempotent by construction
      return ignored("approval answer for a call that is not awaiting approval");
    }
    var settled =
        answer.approved()
            ? working.with(answer.callId(), new SpikeCallPhase.Running())
            : working.with(answer.callId(), new SpikeCallPhase.Denied(answer.reason()));
    return advance(settled);
  }

  private Effect<SpikeTurnState> onToolFinished(SpikeTurnState state, ToolFinished finished) {
    if (!(state instanceof SpikeTurnState.WorkingTools working)) {
      return ignored("tool result while not working tools");
    }
    var call = working.call(finished.callId());
    if (call.isEmpty() || !(call.get().phase() instanceof SpikeCallPhase.Running)) {
      // the at-least-once tail: a re-fired tool landing on a call that already settled
      return ignored("tool result for a call that is not running");
    }
    return advance(working.with(finished.callId(), new SpikeCallPhase.Finished(finished.result())));
  }

  /** The turn-level decision: once every call has settled, go back to the model. */
  private Effect<SpikeTurnState> advance(SpikeTurnState.WorkingTools working) {
    if (!working.allSettled()) {
      return Effect().persist(working).thenRun(() -> dispatch(working));
    }
    var next = new SpikeTurnState.CallingModel(working.transcriptWithResults());
    return Effect().persist(next).thenRun(() -> callModel(next));
  }

  // ------------------------------------------------------------------------------------------
  // Effects — everything here runs AFTER the state above has been durably written
  // ------------------------------------------------------------------------------------------

  private void callModel(SpikeTurnState.CallingModel state) {
    context.pipeToSelf(
        model.reply(state.transcript()),
        (reply, failure) ->
            failure == null
                ? new ModelReplied(reply)
                : new ModelReplied(new SpikeModelReply.Said("the model failed: " + failure)));
  }

  /** Run everything that is ready; note everything that is parked and do nothing about it. */
  private void dispatch(SpikeTurnState.WorkingTools state) {
    for (SpikeToolCall call : state.calls()) {
      switch (call.phase()) {
        case SpikeCallPhase.Running _ -> runTool(call);
        case SpikeCallPhase.AwaitingApproval(String question) ->
            SpikeLifecycleLog.note(agentId, "parked " + call.id() + ": " + question);
        case SpikeCallPhase.Finished _, SpikeCallPhase.Denied _ -> {
          // settled; owes nothing
        }
      }
    }
  }

  private void runTool(SpikeToolCall call) {
    context.pipeToSelf(
        toolbox.run(call),
        (result, failure) ->
            new ToolFinished(call.id(), failure == null ? result : "failed: " + failure));
  }

  private void askShardToPassivate() {
    shard.tell(new ClusterSharding.Passivate<>(context.getSelf()));
  }

  private Effect<SpikeTurnState> ignored(String why) {
    context.getLog().debug("[spike] {} ignored: {}", agentId, why);
    return Effect().none();
  }

  // ------------------------------------------------------------------------------------------
  // Rehydration — the one thing Pekko does not decide for us
  // ------------------------------------------------------------------------------------------

  @Override
  public SignalHandler<SpikeTurnState> signalHandler() {
    return newSignalHandlerBuilder()
        .onSignal(RecoveryCompleted.instance(), this::resume)
        .onSignal(
            PostStop.instance(),
            state -> SpikeLifecycleLog.note(agentId, "stopped while " + name(state)))
        .build();
  }

  /**
   * Pekko brings the state back; what the state still OWES is ours to say. This is the whole of the
   * "who drives a turn to completion after a crash" problem that survives the move to Pekko — and
   * it is four lines rather than a lease table and a sweeper, because the entity that must re-fire
   * is by definition the one being woken.
   *
   * <p>The re-fire is at-least-once, exactly as it always was: a tool that ran and died on the way
   * home will be run again. Nothing in Pekko changes that; only a memoised outcome would.
   */
  private void resume(SpikeTurnState state) {
    SpikeLifecycleLog.note(agentId, "rehydrated while " + name(state));
    switch (state) {
      case SpikeTurnState.Idle _ -> {
        // nothing in flight
      }
      case SpikeTurnState.CallingModel calling -> callModel(calling);
      case SpikeTurnState.WorkingTools working -> dispatch(working);
    }
  }

  private static String name(SpikeTurnState state) {
    return switch (state) {
      case SpikeTurnState.Idle _ -> "idle";
      case SpikeTurnState.CallingModel _ -> "calling the model";
      case SpikeTurnState.WorkingTools working ->
          working.allSettled() ? "working tools (all settled)" : "working tools";
    };
  }
}
