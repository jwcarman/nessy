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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ToolBinding;

/**
 * The approve phase of one tool call: ask, and wait if asked to wait.
 *
 * <p><b>It exists for every call</b>, including the ones nobody gates — an ungated binding is
 * approved by {@code Approver.always()}, which returns immediately and this actor stops. That
 * uniformity is what gives the sequencer above one code path instead of a branch, and it means
 * every decision has exactly one place it can be reported from.
 *
 * <p><b>Fail closed.</b> An approver that throws becomes a denial naming the failure, never an
 * approval and never an escaped exception. {@code ApprovalResult} has no third arm on purpose:
 * there is nowhere for "inconclusive" to land, so a broken approver cannot let a call through.
 *
 * <p>The deadline is held HERE, by the actor that is waiting on it, which is only sound because the
 * agent does not passivate while a turn is in flight.
 */
final class ApprovalActor {

  sealed interface Command {}

  /** The answer, relayed in from the world. */
  record Answer(ApprovalResult result) implements Command {}

  /** The approver came back. Private: only this actor's own call produces it. */
  private record Decided(ApprovalResult result) implements Command {}

  /** The approver deferred, and the term is running. Private. */
  private record Deferred(Instant expiresAt) implements Command {}

  /** The approver threw. Private. */
  private record Broke(String reason) implements Command {}

  /** The term ran out. Private: only this actor's own timer sends it. */
  private record Expired() implements Command {}

  private ApprovalActor() {}

  static Behavior<Command> create(
      ToolBindings bindings,
      ToolBinding<?> binding,
      ApprovalRequest request,
      org.jwcarman.nessy.api.tool.ApprovalContext approvalContext,
      Narrator narrator,
      Executor blocking,
      ActorRef<ToolCallActor.Command> replyTo) {
    return Behaviors.setup(
        context -> {
          // Typed explicitly: left to inference the pipe's value collapses to Object and the
          // pattern match below stops being checked.
          CompletableFuture<Awaited<ApprovalResult>> asked =
              CompletableFuture.supplyAsync(
                  () -> bindings.approve(binding, request, approvalContext), blocking);
          context.pipeToSelf(
              asked,
              (answer, failure) -> {
                if (failure != null) {
                  return new Broke(describe(failure));
                }
                return switch (answer) {
                  case Awaited.Ready<ApprovalResult> ready -> new Decided(ready.result());
                  case Awaited.Deferred<ApprovalResult> deferred ->
                      new Deferred(deferred.expiresAt());
                };
              });
          return waiting(replyTo, request, narrator);
        });
  }

  private static Behavior<Command> waiting(
      ActorRef<ToolCallActor.Command> replyTo, ApprovalRequest request, Narrator narrator) {
    return Behaviors.receive(Command.class)
        .onMessage(Decided.class, decided -> settle(replyTo, decided.result()))
        .onMessage(Answer.class, answer -> settle(replyTo, answer.result()))
        .onMessage(
            Broke.class,
            broke ->
                settle(replyTo, ApprovalResult.denied("the approver failed: " + broke.reason())))
        .onMessage(
            Deferred.class,
            deferred -> awaitingHuman(replyTo, request, narrator, deferred.expiresAt()))
        .build();
  }

  /**
   * A person is being waited on. The term is measured from now against the instant the approver
   * asked for; a deadline already past fires immediately rather than waiting a negative duration.
   */
  private static Behavior<Command> awaitingHuman(
      ActorRef<ToolCallActor.Command> replyTo,
      ApprovalRequest request,
      Narrator narrator,
      Instant expiresAt) {
    // The only event a watcher can ACT on: someone is being waited for, and it is them.
    narrator.narrate(
        new org.jwcarman.nessy.api.AgentEvent.ApprovalRequested(
            Identifiers.next(),
            request.call().id(),
            request.call().name(),
            request.description(),
            expiresAt));
    Duration remaining = Duration.between(Instant.now(), expiresAt);
    Duration wait = remaining.isNegative() ? Duration.ZERO : remaining;
    return Behaviors.withTimers(
        timers -> {
          timers.startSingleTimer(new Expired(), wait);
          return Behaviors.receive(Command.class)
              .onMessage(Answer.class, answer -> settle(replyTo, answer.result()))
              .onMessage(
                  Expired.class,
                  expired ->
                      settle(replyTo, ApprovalResult.denied("nobody answered by " + expiresAt)))
              .build();
        });
  }

  private static Behavior<Command> settle(
      ActorRef<ToolCallActor.Command> replyTo, ApprovalResult result) {
    replyTo.tell(new ToolCallActor.Answered(result));
    return Behaviors.stopped();
  }

  private static String describe(Throwable failure) {
    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
    String message = cause.getMessage();
    return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
