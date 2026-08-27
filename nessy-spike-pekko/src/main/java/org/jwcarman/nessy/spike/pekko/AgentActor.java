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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.state.RecoveryCompleted;
import org.apache.pekko.persistence.typed.state.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.state.javadsl.DurableStateBehavior;
import org.apache.pekko.persistence.typed.state.javadsl.Effect;
import org.apache.pekko.persistence.typed.state.javadsl.SignalHandler;

/**
 * THROWAWAY SPIKE. One agent: the turn, its phase, and the children doing the work.
 *
 * <p><b>This class contains NO cluster type.</b> That is the round-3 thesis in one sentence — the
 * behaviour is written against the actor and persistence model only, so the very same file runs
 * under the single-node registry ({@link SpikeRegistry}) and under Cluster Sharding, with only the
 * LOOKUP differing. It is compiled in a module that has no {@code pekko-cluster} dependency at all,
 * so this is enforced by Maven rather than by good intentions.
 *
 * <p><b>What decomposition took away.</b> Round 2's version of this file had to route tool-call
 * events by id into a per-call sealed phase, decide admissibility per state, and rebuild
 * outstanding effects per variant. All of that is gone. What is left:
 *
 * <pre>
 *   Observe          Idle          -&gt; CallingModel   ... and ask the desk
 *   ModelReplied     CallingModel  -&gt; WorkingTools   ... and spawn one actor per call
 *                                  -&gt; Idle           ... if the model just talked
 *   AnswerApproval   WorkingTools  -&gt; (NO STATE CHANGE) relay to that call's actor
 *   ToolCallSettled  WorkingTools  -&gt; WorkingTools   ... or back to CallingModel when all settled
 * </pre>
 *
 * Note the third line especially: an approval answer no longer touches the agent's document at all.
 * It is somebody else's business, and the agent's only job is to know whose.
 */
public final class AgentActor extends DurableStateBehavior<AgentActor.Command, SpikeTurnState> {

  /**
   * How a runtime asks for this agent to be let go — passivation in sharding, stop in the registry.
   */
  @FunctionalInterface
  public interface StopRequest {
    void requestStop(String agentId, ActorRef<Command> self);
  }

  public sealed interface Command extends SpikeSerializable {}

  /** From the world: something happened. */
  public record Observe(String text) implements Command {}

  /** From the world, out of band, possibly days later, possibly to a different JVM. */
  public record AnswerApproval(String callId, boolean approved, String reason) implements Command {}

  /** From the world: what does this agent look like right now? */
  public record Inspect(ActorRef<SpikeTurnState> replyTo) implements Command {}

  /** From a model worker. */
  public record ModelReplied(SpikeModelReply reply) implements Command {}

  /** From one of this agent's own {@link ToolCallActor} children. */
  public record ToolCallSettled(String callId, String outcome) implements Command {}

  /** From the startup sweep: exists only to bring the actor into memory so recovery can run. */
  public record Wake() implements Command {}

  /** From the world: let go of memory. */
  public record Rest() implements Command {}

  /** From the runtime, in response to {@link Rest}. The only message that ends the actor. */
  public record Stop() implements Command {}

  public static final Stop STOP = new Stop();

  private final ActorContext<Command> context;
  private final String agentId;
  private final SpikeToolbox toolbox;
  private final ActorRef<SpikeModelDesk.Command> modelDesk;
  private final ActorRef<SpikeToolWorker.RunTool> tools;
  private final Duration approvalTerm;
  private final StopRequest stopRequest;

  /**
   * Live children, by call id. Deliberately NOT persisted and deliberately not derived from {@code
   * context.getChild(name)}, which returns an untyped ref that cannot be told a typed message.
   * Rebuilt on spawn and on recovery; an empty map after a restart is correct, because after a
   * restart there are no children.
   */
  private final Map<String, ActorRef<ToolCallActor.Command>> callActors = new HashMap<>();

  public static Behavior<Command> create(
      String agentId,
      SpikeToolbox toolbox,
      ActorRef<SpikeModelDesk.Command> modelDesk,
      ActorRef<SpikeToolWorker.RunTool> tools,
      Duration approvalTerm,
      StopRequest stopRequest) {
    return Behaviors.setup(
        context ->
            new AgentActor(context, agentId, toolbox, modelDesk, tools, approvalTerm, stopRequest));
  }

  private AgentActor(
      ActorContext<Command> context,
      String agentId,
      SpikeToolbox toolbox,
      ActorRef<SpikeModelDesk.Command> modelDesk,
      ActorRef<SpikeToolWorker.RunTool> tools,
      Duration approvalTerm,
      StopRequest stopRequest) {
    super(PersistenceId.of("SpikeTurn", agentId));
    this.context = context;
    this.agentId = agentId;
    this.toolbox = toolbox;
    this.modelDesk = modelDesk;
    this.tools = tools;
    this.approvalTerm = approvalTerm;
    this.stopRequest = stopRequest;
  }

  @Override
  public SpikeTurnState emptyState() {
    return SpikeTurnState.Idle.empty();
  }

  @Override
  public CommandHandler<Command, SpikeTurnState> commandHandler() {
    return (state, command) ->
        switch (command) {
          case Inspect inspect -> Effect().none().thenRun(() -> inspect.replyTo().tell(state));
          case Wake _ -> Effect().none();
          case Rest _ ->
              Effect().none().thenRun(() -> stopRequest.requestStop(agentId, context.getSelf()));
          case Stop _ -> Effect().none().thenStop();
          case Observe observe -> onObserve(state, observe);
          case ModelReplied replied -> onModelReplied(state, replied);
          case AnswerApproval answer -> onAnswerApproval(state, answer);
          case ToolCallSettled settled -> onToolCallSettled(state, settled);
        };
  }

  private Effect<SpikeTurnState> onObserve(SpikeTurnState state, Observe observe) {
    if (!(state instanceof SpikeTurnState.Idle)) {
      return Effect().none();
    }
    var next =
        new SpikeTurnState.CallingModel(
            SpikeTurnState.plus(state.transcript(), "user: " + observe.text()));
    return Effect().persist(next).thenRun(() -> askModel(next));
  }

  private Effect<SpikeTurnState> onModelReplied(SpikeTurnState state, ModelReplied replied) {
    if (!(state instanceof SpikeTurnState.CallingModel)) {
      return Effect().none();
    }
    return switch (replied.reply()) {
      case SpikeModelReply.Said(String text) ->
          Effect()
              .persist(
                  new SpikeTurnState.Idle(
                      SpikeTurnState.plus(state.transcript(), "assistant: " + text)));
      case SpikeModelReply.AskedForTools(var requests) -> {
        var calls =
            requests.stream()
                .map(r -> SpikeToolCall.asked(r.id(), r.tool(), r.argument()))
                .toList();
        var next =
            new SpikeTurnState.WorkingTools(
                SpikeTurnState.plus(
                    state.transcript(),
                    "assistant: (asked for "
                        + calls.stream().map(SpikeToolCall::tool).toList()
                        + ")"),
                calls);
        yield Effect().persist(next).thenRun(() -> spawnMissing(next));
      }
    };
  }

  /**
   * <b>No persist.</b> The agent does not know or care whether this call is awaiting approval,
   * running, or already finished — its actor does, and a behaviour that has moved on simply has no
   * case for the message. Round 2 needed a state machine here to decide the same thing.
   */
  private Effect<SpikeTurnState> onAnswerApproval(SpikeTurnState state, AnswerApproval answer) {
    if (!(state instanceof SpikeTurnState.WorkingTools)) {
      return Effect().none();
    }
    return Effect()
        .none()
        .thenRun(
            () -> {
              var child = callActors.get(answer.callId());
              if (child == null) {
                context.getLog().debug("[spike] no live actor for call {}", answer.callId());
                return;
              }
              child.tell(new ToolCallActor.Answer(answer.approved(), answer.reason()));
            });
  }

  private Effect<SpikeTurnState> onToolCallSettled(SpikeTurnState state, ToolCallSettled settled) {
    if (!(state instanceof SpikeTurnState.WorkingTools working)) {
      return Effect().none();
    }
    var call = working.call(settled.callId());
    if (call.isEmpty() || call.get().settled()) {
      return Effect().none(); // the at-least-once tail: a duplicate outcome
    }
    callActors.remove(settled.callId());
    var updated = working.settle(settled.callId(), settled.outcome());
    if (!updated.allSettled()) {
      return Effect().persist(updated);
    }
    var next = new SpikeTurnState.CallingModel(updated.transcriptWithResults());
    return Effect().persist(next).thenRun(() -> askModel(next));
  }

  // ------------------------------------------------------------------------------------------
  // Effects — all AFTER the state above has been durably written
  // ------------------------------------------------------------------------------------------

  /** A plain fire-and-forget. The desk owns every scrap of the work-pulling protocol. */
  private void askModel(SpikeTurnState.CallingModel state) {
    modelDesk.tell(new SpikeModelDesk.CallModel(state.transcript(), context.getSelf()));
  }

  /**
   * Spawn an actor for every call that has no outcome and no live child. This is idempotent by
   * construction, which is what lets it serve as both the normal path and the recovery path.
   */
  private void spawnMissing(SpikeTurnState.WorkingTools state) {
    for (SpikeToolCall call : state.unsettled()) {
      callActors.computeIfAbsent(
          call.id(),
          id ->
              context.spawn(
                  ToolCallActor.create(call, context.getSelf(), toolbox, tools, approvalTerm),
                  ToolCallActor.nameFor(id)));
    }
  }

  // ------------------------------------------------------------------------------------------
  // Rehydration
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
   * The whole re-fire rule. Compare round 2, which needed an {@code outstanding()} method on every
   * phase AND on every call state, with a documented argument about which effect to re-issue.
   */
  private void resume(SpikeTurnState state) {
    SpikeLifecycleLog.note(agentId, "rehydrated while " + name(state));
    switch (state) {
      case SpikeTurnState.Idle _ -> {
        // nothing in flight
      }
      case SpikeTurnState.CallingModel calling -> askModel(calling);
      case SpikeTurnState.WorkingTools working -> spawnMissing(working);
    }
  }

  private static String name(SpikeTurnState state) {
    return switch (state) {
      case SpikeTurnState.Idle _ -> "idle";
      case SpikeTurnState.CallingModel _ -> "calling the model";
      case SpikeTurnState.WorkingTools _ -> "working tools";
    };
  }
}
