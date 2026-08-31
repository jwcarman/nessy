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

import java.time.Instant;
import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.tool.ApprovalContext;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolBinding;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * ONE tool call's whole life, as a sequencer: ask, then run.
 *
 * <p>It decides nothing itself. Each phase has its own actor, each of which calls its seam, holds
 * its own deadline, reports back, and dies — so this reads as the four lines a person would ask
 * them in, with no state machine and no admission matrix.
 *
 * <p><b>Both phase actors always exist.</b> A tool nobody gates is approved by {@code
 * Approver.always()}, and its approval actor stops almost immediately. That uniformity is what
 * removes the branch here — there is no "does this need an approver" question — and it is what left
 * deferral homeless when execution alone had no actor.
 */
final class ToolCallActor {

  sealed interface Command {}

  /** From this call's own approval actor. */
  record Answered(ApprovalResult result) implements Command {}

  /** From this call's own execution actor. */
  record Ran(ToolResult result) implements Command {}

  /** Relayed in from the world, by way of a reply token. */
  record RelayApproval(ApprovalResult result) implements Command {}

  /** Relayed in from the world, by way of a reply token. */
  record RelayResult(ToolResult result) implements Command {}

  private ToolCallActor() {}

  static Behavior<Command> create(
      AgentType agentType,
      AgentId agentId,
      ToolCall call,
      ToolBindings bindings,
      Narrator narrator,
      ReplyTokens tokens,
      Executor blocking,
      ActorRef<TurnActor.Command> turn) {
    return Behaviors.setup(
        context -> {
          ToolBinding<?> binding = bindings.binding(call.name()).orElse(null);
          if (binding == null) {
            // Nothing ran, and nothing can: the model named a tool this agent was never granted.
            turn.tell(
                new TurnActor.ToolSettled(
                    call.id(),
                    ToolResult.error("no such tool: " + call.name() + "; the call was not made")));
            return Behaviors.stopped();
          }
          String description = bindings.describe(binding, call.arguments());
          narrator.narrate(
              new AgentEvent.ToolCallRequested(
                  Identifiers.next(), call.id(), call.name(), description));
          ApprovalRequest request =
              new ApprovalRequest(agentType, agentId, call, description, Instant.now());
          // One address for this call, minted before anyone is asked: the approver may hand it to
          // a person, and the tool may hand it to the outside world. Both settle the same call, so
          // both get the same token rather than two that mean the same thing.
          ReplyToken replyAddress = tokens.mint(agentType, agentId, call.id());
          ApprovalContext approvalContext = () -> replyAddress;
          ToolContext toolContext = () -> replyAddress;
          ActorRef<ApprovalActor.Command> approval =
              context.spawn(
                  ApprovalActor.create(
                      bindings,
                      binding,
                      request,
                      approvalContext,
                      narrator,
                      blocking,
                      context.getSelf()),
                  "approval");
          return awaitingApproval(
              agentType,
              agentId,
              call,
              bindings,
              binding,
              narrator,
              toolContext,
              blocking,
              turn,
              approval);
        });
  }

  private static Behavior<Command> awaitingApproval(
      AgentType agentType,
      AgentId agentId,
      ToolCall call,
      ToolBindings bindings,
      ToolBinding<?> binding,
      Narrator narrator,
      ToolContext toolContext,
      Executor blocking,
      ActorRef<TurnActor.Command> turn,
      ActorRef<ApprovalActor.Command> approval) {
    return Behaviors.receive(Command.class)
        .onMessage(
            RelayApproval.class,
            relayed -> {
              approval.tell(new ApprovalActor.Answer(relayed.result()));
              return Behaviors.same();
            })
        .onMessage(
            Answered.class,
            answered -> {
              narrator.narrate(
                  new AgentEvent.ApprovalDecided(
                      Identifiers.next(), call.id(), call.name(), answered.result()));
              return switch (answered.result()) {
                case ApprovalResult.Denied denied ->
                    settle(
                        call,
                        narrator,
                        turn,
                        ToolResult.error("denied: " + denied.reason() + "; the call was not made"));
                case ApprovalResult.Approved approved ->
                    Behaviors.setup(
                        context -> {
                          ActorRef<ExecutionActor.Command> execution =
                              context.spawn(
                                  ExecutionActor.create(
                                      bindings,
                                      binding,
                                      call.arguments(),
                                      toolContext,
                                      blocking,
                                      context.getSelf()),
                                  "execution");
                          return awaitingExecution(call, narrator, turn, execution);
                        });
              };
            })
        .build();
  }

  private static Behavior<Command> awaitingExecution(
      ToolCall call,
      Narrator narrator,
      ActorRef<TurnActor.Command> turn,
      ActorRef<ExecutionActor.Command> execution) {
    return Behaviors.receive(Command.class)
        .onMessage(
            RelayResult.class,
            relayed -> {
              execution.tell(new ExecutionActor.Answer(relayed.result()));
              return Behaviors.same();
            })
        .onMessage(Ran.class, ran -> settle(call, narrator, turn, ran.result()))
        .build();
  }

  /** One place a call ends, so it cannot be reported to the turn without also being narrated. */
  private static Behavior<Command> settle(
      ToolCall call, Narrator narrator, ActorRef<TurnActor.Command> turn, ToolResult result) {
    narrator.narrate(
        new AgentEvent.ToolCallCompleted(Identifiers.next(), call.id(), call.name(), result));
    turn.tell(new TurnActor.ToolSettled(call.id(), result));
    return Behaviors.stopped();
  }
}
