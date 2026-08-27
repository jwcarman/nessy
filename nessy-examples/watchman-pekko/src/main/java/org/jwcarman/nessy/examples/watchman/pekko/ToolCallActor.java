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
package org.jwcarman.nessy.examples.watchman.pekko;

import java.time.Duration;
import java.util.Map;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * ONE tool call's entire life, as a behaviour. Ephemeral child of {@link AgentActor}.
 *
 * <p>Read {@link #create} top to bottom and the whole decision table is four lines, in the order a
 * human would ask them: has a human already answered? did they say no? does this tool even need a
 * human? otherwise, run it. There is no {@code AwaitingApproval | Running | Denied} enumeration, no
 * admission matrix, and no per-state re-fire rule — a behaviour that has moved on simply has no
 * case for a message that no longer applies.
 *
 * <p><b>The first branch is what makes recovery trivial.</b> Because a human's decision is
 * persisted by the agent before anyone is told it was accepted, an actor respawned after a crash
 * finds the answer already in its own record and proceeds without asking again. That is the whole
 * of the "re-ask is safe because nobody was told" argument, replaced by "we do not have to re-ask".
 */
public final class ToolCallActor {

  public sealed interface Command {}

  /** From the world, relayed by the agent: the human answered. */
  public record Answer(boolean approved, String by, String note) implements Command {}

  /** From this call's own {@link ApprovalActor}. */
  public record Answered(boolean approved, String by, String note) implements Command {}

  /** From a tool worker: the command came back. */
  public record Ran(String result) implements Command {}

  private ToolCallActor() {}

  /** The name a call's actor is known by, so the agent can find it again to relay an answer. */
  public static String nameFor(String callId) {
    return "call-" + callId.replaceAll("[^A-Za-z0-9-]", "_");
  }

  public static Behavior<Command> create(
      ToolCallRecord call,
      ActorRef<AgentActor.Command> agent,
      ActorRef<ToolWorker.RunTool> tools,
      Duration approvalTerm,
      Map<String, String> trace,
      java.time.Clock clock) {

    return Behaviors.setup(
        context -> {
          if (call.decided() && call.decision().approved()) {
            return run(call, agent, tools, context.getSelf(), trace);
          }
          if (call.decided()) {
            agent.tell(
                new AgentActor.ToolCallSettled(
                    call.id(),
                    "denied by " + call.decision().by() + ": " + call.decision().note(),
                    trace));
            return Behaviors.stopped();
          }
          if (WatchmanTools.needsApproval(call.tool())) {
            ActorRef<ApprovalActor.Command> approval =
                context.spawn(
                    ApprovalActor.create(call, approvalTerm, clock.instant(), context.getSelf()),
                    "approval");
            return awaitingApproval(call, agent, tools, approval, trace);
          }
          return run(call, agent, tools, context.getSelf(), trace);
        });
  }

  /**
   * The park. One small actor, no thread, and no row anywhere saying "awaiting approval" — the
   * agent persists only that this call has no outcome yet, plus the decision once one arrives.
   */
  private static Behavior<Command> awaitingApproval(
      ToolCallRecord call,
      ActorRef<AgentActor.Command> agent,
      ActorRef<ToolWorker.RunTool> tools,
      ActorRef<ApprovalActor.Command> approval,
      Map<String, String> trace) {
    return Behaviors.receive(Command.class)
        .onMessage(
            Answer.class,
            answer -> {
              approval.tell(
                  new ApprovalActor.Answer(answer.approved(), answer.by(), answer.note()));
              return Behaviors.same();
            })
        .onMessage(
            Answered.class,
            answered -> {
              if (!answered.approved()) {
                agent.tell(
                    new AgentActor.ToolCallSettled(
                        call.id(), "denied by " + answered.by() + ": " + answered.note(), trace));
                return Behaviors.stopped();
              }
              return Behaviors.setup(context -> run(call, agent, tools, context.getSelf(), trace));
            })
        .build();
  }

  private static Behavior<Command> run(
      ToolCallRecord call,
      ActorRef<AgentActor.Command> agent,
      ActorRef<ToolWorker.RunTool> tools,
      ActorRef<Command> self,
      Map<String, String> trace) {
    tools.tell(new ToolWorker.RunTool(call, self, trace));
    return Behaviors.receive(Command.class)
        .onMessage(
            Ran.class,
            ran -> {
              agent.tell(new AgentActor.ToolCallSettled(call.id(), ran.result(), trace));
              return Behaviors.stopped();
            })
        // An answer for a call already running is a duplicate; ignoring it here is the whole of
        // round 2's "is this event admissible for this state?" logic.
        .onMessage(Answer.class, answer -> Behaviors.same())
        .build();
  }
}
