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
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * THROWAWAY SPIKE. ONE tool call's entire life, as a behaviour. Ephemeral child of the agent.
 *
 * <p><b>This file is the round-3 headline.</b> Everything it does used to be data inside the
 * agent's persisted document: a sealed {@code SpikeCallPhase} of four variants, a routing method
 * that found "whose fact this is", a per-variant admission matrix deciding which events each state
 * would accept, and an {@code outstanding()} rule per variant saying what to re-fire. All of it
 * existed for one reason — a single document had to hold N concurrent call lifecycles at once.
 *
 * <p>Given an actor per call, that reason evaporates. "Awaiting approval" is not a value in a map,
 * it is {@link #awaitingApproval}: a behaviour that accepts exactly one message. Nothing has to
 * decide whether an answer is admissible for the state a call happens to be in, because a behaviour
 * that does not accept a message simply does not have a case for it. The admission matrix is the
 * behaviour transitions, and it is checked by the compiler.
 *
 * <p>Failure isolation comes with it: this actor can die, restart, or be supervised without the
 * agent — or its five sibling calls — knowing anything about it.
 */
public final class ToolCallActor {

  public sealed interface Command {}

  /** From the world, relayed by the agent: the human answered. */
  public record Answer(boolean approved, String reason) implements Command {}

  /** From this call's own {@link ApprovalActor}. */
  public record Answered(boolean approved, String reason) implements Command {}

  /** From a model/tool worker: the tool came back. */
  public record Ran(String result) implements Command {}

  private ToolCallActor() {}

  /** The name a call's actor is known by, so the agent can find it again to relay an answer. */
  public static String nameFor(String callId) {
    return "call-" + callId.replaceAll("[^A-Za-z0-9-]", "_");
  }

  public static Behavior<Command> create(
      SpikeToolCall call,
      ActorRef<AgentActor.Command> agent,
      SpikeToolbox toolbox,
      ActorRef<SpikeToolWorker.RunTool> tools,
      Duration approvalTerm) {
    return Behaviors.setup(
        context -> {
          if (toolbox.needsApproval(call.tool())) {
            ActorRef<ApprovalActor.Command> approval =
                context.spawn(
                    ApprovalActor.create(
                        call.id(), toolbox.questionFor(call), approvalTerm, context.getSelf()),
                    "approval");
            return awaitingApproval(call, agent, tools, approval);
          }
          tools.tell(new SpikeToolWorker.RunTool(call, context.getSelf()));
          return running(call, agent);
        });
  }

  /**
   * The park. One live actor, no thread, no timer of its own (the {@link ApprovalActor} holds
   * that), and — the part that matters — no row anywhere saying "this call is awaiting approval".
   * The agent persists only that the call has no outcome yet.
   */
  private static Behavior<Command> awaitingApproval(
      SpikeToolCall call,
      ActorRef<AgentActor.Command> agent,
      ActorRef<SpikeToolWorker.RunTool> tools,
      ActorRef<ApprovalActor.Command> approval) {
    return Behaviors.receive(Command.class)
        .onMessage(
            Answer.class,
            answer -> {
              approval.tell(new ApprovalActor.Answer(answer.approved(), answer.reason()));
              return Behaviors.same();
            })
        .onMessage(
            Answered.class,
            answered -> {
              if (!answered.approved()) {
                agent.tell(
                    new AgentActor.ToolCallSettled(call.id(), "denied: " + answered.reason()));
                return Behaviors.stopped();
              }
              return Behaviors.setup(
                  context -> {
                    tools.tell(new SpikeToolWorker.RunTool(call, context.getSelf()));
                    return running(call, agent);
                  });
            })
        .build();
  }

  private static Behavior<Command> running(SpikeToolCall call, ActorRef<AgentActor.Command> agent) {
    return Behaviors.receive(Command.class)
        .onMessage(
            Ran.class,
            ran -> {
              agent.tell(new AgentActor.ToolCallSettled(call.id(), ran.result()));
              return Behaviors.stopped();
            })
        // An answer arriving for a call already running is a duplicate. Ignoring it here is the
        // whole of round 2's "is this event admissible?" logic for this state.
        .onMessage(Answer.class, answer -> Behaviors.same())
        .build();
  }
}
