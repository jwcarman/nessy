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

import java.util.Map;
import java.util.Objects;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.state.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.state.javadsl.DurableStateBehavior;
import org.apache.pekko.persistence.typed.state.javadsl.Effect;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.agent.AgentLogic;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Decision;
import org.jwcarman.nessy.engine.agent.Input;
import org.jwcarman.nessy.engine.agent.Instruction;

/**
 * One agent, one actor, one document.
 *
 * <p>There used to be five actor types to work one turn, and two defects came out of that shape
 * rather than out of any coding mistake. A turn was a CHILD, so it died with its agent — an agent
 * unloaded the instant a turn ended would kill a model call already in flight, and nothing was left
 * to finish it. And a deadline lived in an actor's TIMER, so an approval parked on a person for
 * three days depended on a process staying up.
 *
 * <p><b>This class does four things and nothing else:</b> translate a message into an {@link
 * Input}, call {@link AgentLogic#decide}, persist what it returns, and hand the instructions to
 * {@link Instructions}. Every rule lives in the logic, which has no way to do anything; every
 * effect lives in the shell, which decides nothing.
 *
 * <p><b>Why the revival is gone.</b> {@code onStop} used to post a {@code Wake} to its own entity
 * id so a successor would pick up a stranded turn. Slow work now addresses its answer to the
 * agent's LOGICAL address, so the answer arriving is itself the knock that revives the agent — the
 * message that had to be invented is the message that was always coming.
 */
public final class AgentActor extends DurableStateBehavior<NessyMessage, AgentState> {

  private final ActorContext<NessyMessage> context;
  private final AgentType agentType;
  private final AgentId agentId;
  private final Instructions instructions;
  private final Traces traces;
  private final ActorRef<ClusterSharding.ShardCommand> shard;

  private AgentActor(
      ActorContext<NessyMessage> context,
      Dependencies deps,
      AgentId agentId,
      ActorRef<ClusterSharding.ShardCommand> shard) {
    super(PersistenceId.of(deps.agentType().name(), agentId.value()));
    this.context = context;
    this.agentType = deps.agentType();
    this.agentId = agentId;
    this.instructions = deps.instructions();
    this.traces = deps.traces();
    this.shard = shard;
  }

  /** What one KIND of agent needs. The observation type is not here: the backlog store owns it. */
  public record Dependencies(AgentType agentType, Instructions instructions, Traces traces) {}

  public static Behavior<NessyMessage> create(
      Dependencies deps, AgentId agentId, ActorRef<ClusterSharding.ShardCommand> shard) {
    Objects.requireNonNull(deps, "deps must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(shard, "shard must not be null");
    return Behaviors.setup(
        context -> {
          // Recovery is not a mode. Pekko has already read the document by the time any command
          // runs, so the agent asks itself what to do about it on EVERY activation — which makes
          // the rare path the common path, exercised constantly rather than only after a crash.
          context.getSelf().tell(new NessyMessage.Recovered(Map.of()));
          return new AgentActor(context, deps, agentId, shard);
        });
  }

  @Override
  public AgentState emptyState() {
    return AgentState.idle();
  }

  /**
   * Every message handled inside a CONSUMER span parented to whatever the sender carried.
   *
   * <p>CONSUMER because the mailbox is a queue: paired with the send, the gap between them is queue
   * latency, which is exactly the thing an actor system makes easy to have and hard to see.
   *
   * <p><b>Wrapped HERE and not by a {@code BehaviorInterceptor}</b>, which was tried and reverted.
   * An interceptor's {@code aroundReceive} does not enclose a {@link DurableStateBehavior}'s
   * command handler — the handler runs outside that scope — so a capture inside a handler came back
   * empty and the whole tree collapsed to two spans. Measured, not reasoned about.
   */
  @Override
  public CommandHandler<NessyMessage, AgentState> commandHandler() {
    return newCommandHandlerBuilder()
        .forAnyState()
        .onAnyCommand(
            (state, message) ->
                traces.inSpan(
                    "agent receive " + message.getClass().getSimpleName(),
                    message.headers(),
                    () -> {
                      describe(message);
                      return onMessage(state, message);
                    }));
  }

  /** Translate, decide, persist, run. */
  private Effect<AgentState> onMessage(AgentState state, NessyMessage message) {
    if (message instanceof NessyMessage.Inspect inspect) {
      inspect.replyTo().tell(state);
      return Effect().none();
    }
    if (message instanceof NessyMessage.Stop) {
      return Effect().none().thenStop();
    }
    acknowledge(state, message);
    Decision decision = AgentLogic.decide(state, inputOf(state, message));
    Map<String, String> carried = message.headers();
    return Effect()
        .persist(decision.next())
        .thenRun(
            next -> {
              decision.then().forEach(each -> run(next, each, carried));
              sleepIfAsked(next, decision);
            });
  }

  /**
   * Sleeping is the one instruction the shell cannot perform, because it needs this actor's own
   * handle — the shard is told to unload THIS incarnation, and nothing outside holds it.
   */
  private void sleepIfAsked(AgentState state, Decision decision) {
    if (!state.busy() && decision.then().stream().anyMatch(Instruction.Sleep.class::isInstance)) {
      shard.tell(new ClusterSharding.Passivate<>(context.getSelf()));
    }
  }

  /**
   * Tells an outside caller whether its answer landed on a call still waiting for one.
   *
   * <p>A reply token that reads cleanly says only that this engine issued it, never that the call
   * is still open — so a person clicking a stale button, or a vendor answering a call that already
   * timed out, gets told so rather than silently changing nothing.
   */
  private void acknowledge(AgentState state, NessyMessage message) {
    switch (message) {
      case NessyMessage.ToolAnswered answered ->
          answered.replyTo().tell(waitingFor(state, answered.callId()));
      case NessyMessage.ApprovalAnswered answered ->
          answered.replyTo().tell(waitingFor(state, answered.callId()));
      default -> {
        // Nothing outside is waiting on this one.
      }
    }
  }

  private static NessyMessage.Ack waitingFor(AgentState state, String callId) {
    if (!(state.phase() instanceof org.jwcarman.nessy.engine.agent.Phase.WorkingTools working)
        || !working.calls().containsKey(callId)) {
      return new NessyMessage.Ack(false, "no call \"" + callId + "\" is waiting for an answer");
    }
    return new NessyMessage.Ack(true, "accepted");
  }

  private void run(AgentState state, Instruction instruction, Map<String, String> carried) {
    instructions.perform(agentId, state, instruction, carried);
  }

  /**
   * What happened, from what arrived.
   *
   * <p>A message and an input are one-to-one on purpose: a message with no input to become is a
   * message nothing can act on, and this is where that would show up as a compiler error.
   */
  private static Input inputOf(AgentState state, NessyMessage message) {
    return switch (message) {
      case NessyMessage.BacklogUpdated ignored -> new Input.BacklogUpdated();
      case NessyMessage.WorkTaken taken ->
          new Input.WorkTaken(taken.turnId(), taken.observationClaim());
      case NessyMessage.NoWork ignored -> new Input.NoWork();
      case NessyMessage.Recovered ignored -> new Input.Recovered();
      case NessyMessage.ModelAnswered answered ->
          new Input.ModelAnswered.Answered(answered.stopReason(), answered.usage());
      case NessyMessage.ModelAsked asked ->
          new Input.ModelAnswered.Asked(asked.calls(), asked.usage());
      case NessyMessage.ModelRefused refused ->
          new Input.ModelAnswered.Refused(
              refused.category(), refused.explanation(), refused.usage());
      case NessyMessage.ModelFailed failed -> new Input.ModelFailed(failed.reason());
      case NessyMessage.ApprovalGiven given ->
          new Input.ApprovalGiven(given.callId(), given.toolName(), given.result());
      case NessyMessage.ToolParked parked ->
          new Input.ToolParked(parked.callId(), parked.expiresAt());
      case NessyMessage.ToolCompleted done -> new Input.ToolCompleted(done.callId());
      case NessyMessage.DeadlinePassed passed -> new Input.DeadlinePassed(passed.callId());
      case NessyMessage.ToolAnswered answered -> new Input.ToolCompleted(answered.callId());
      case NessyMessage.ApprovalAnswered answered ->
          // The tool name comes from the STATE rather than the wire: Approving carries it precisely
          // so a decision arriving three days later needs to say nothing but yes or no.
          new Input.ApprovalGiven(
              answered.callId(), nameOf(state, answered.callId()), answered.result());
      case NessyMessage.Inspect ignored -> new Input.Recovered();
      case NessyMessage.Stop ignored -> new Input.SleepNow();
    };
  }

  private static String nameOf(AgentState state, String callId) {
    if (state.phase() instanceof org.jwcarman.nessy.engine.agent.Phase.WorkingTools working
        && working.calls().get(callId)
            instanceof org.jwcarman.nessy.engine.agent.CallState.Approving approving) {
      return approving.toolName();
    }
    return "";
  }

  /** What this actor knows about itself, written onto the receive span. */
  void describe(NessyMessage message) {
    traces.tag("messaging.system", "pekko");
    traces.detail("nessy.agent.id", agentId.value());
    traces.tag("nessy.agent.type", agentType.name());
    traces.tag("nessy.message.type", message.getClass().getSimpleName());
    // Locally this renders "pekko://nessy"; clustered it becomes "pekko://nessy@host:port", so the
    // same attribute answers "which box" the day this is multi-node.
    traces.tag("nessy.node.address", context.getSystem().address().toString());
  }
}
