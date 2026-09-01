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

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ToolBinding;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The execute phase of one tool call: run it, and wait if it says it will answer later.
 *
 * <p>The exact mirror of {@link ApprovalActor}, and that symmetry is the point. Execution used to
 * be handed to a pooled worker, which has no per-call identity — so a tool that deferred had
 * nowhere for its answer to come back TO, and the engine turned {@code Awaited.Deferred} into an
 * error handed to the model. An actor per call is the spot to come back to.
 *
 * <p>Like its sibling, it exists for every call and usually dies immediately.
 */
final class ExecutionActor {

  sealed interface Command {}

  /** The answer, relayed in from the world by way of a reply token. */
  record Answer(ToolResult result) implements Command {}

  private record Produced(ToolResult result) implements Command {}

  private record Deferred(Instant expiresAt) implements Command {}

  private record Broke(String reason) implements Command {}

  private record Expired(Instant deadline) implements Command {}

  private ExecutionActor() {}

  static Behavior<Command> create(
      ToolBindings bindings,
      ToolBinding<?> binding,
      JsonNode arguments,
      ToolContext toolContext,
      Executor blocking,
      ActorRef<ToolCallActor.Command> replyTo,
      Traces traces,
      Map<String, String> carried) {
    return Behaviors.setup(
        context -> {
          CompletableFuture<Awaited<ToolResult>> ran =
              CompletableFuture.supplyAsync(
                  // The span is opened HERE, on the worker thread, from headers carried into the
                  // work — not inherited from the submitting thread. Nothing about this depends on
                  // a thread-local surviving executor.execute, which is what makes it survive a
                  // hop that a captured scope would not.
                  () ->
                      traces.inSpan(
                          "tool " + binding.tool().name(),
                          carried,
                          () -> bindings.run(binding, arguments, toolContext)),
                  blocking);
          context.pipeToSelf(
              ran,
              (answer, failure) -> {
                if (failure != null) {
                  return new Broke(describe(failure));
                }
                return switch (answer) {
                  case Awaited.Ready<ToolResult> ready -> new Produced(ready.result());
                  case Awaited.Deferred<ToolResult> deferred -> new Deferred(deferred.expiresAt());
                };
              });
          return waiting(replyTo);
        });
  }

  private static Behavior<Command> waiting(ActorRef<ToolCallActor.Command> replyTo) {
    return Behaviors.receive(Command.class)
        .onMessage(Produced.class, produced -> settle(replyTo, produced.result()))
        .onMessage(Answer.class, answer -> settle(replyTo, answer.result()))
        .onMessage(
            Broke.class,
            broke ->
                settle(
                    replyTo,
                    ToolResult.error(broke.reason() + "; it may have partially completed")))
        .onMessage(
            Deferred.class,
            deferred -> {
              // A tool can wait on the world just as an approval waits on a person, and the
              // deadline has to outlive this actor either way.
              replyTo.tell(new ToolCallActor.Parked(deferred.expiresAt()));
              return awaitingTheWorld(replyTo, deferred.expiresAt());
            })
        .build();
  }

  private static Behavior<Command> awaitingTheWorld(
      ActorRef<ToolCallActor.Command> replyTo, Instant expiresAt) {
    Duration remaining = Duration.between(Instant.now(), expiresAt);
    Duration wait = remaining.isNegative() ? Duration.ZERO : remaining;
    return Behaviors.withTimers(
        timers -> {
          timers.startSingleTimer(new Expired(expiresAt), wait);
          return Behaviors.receive(Command.class)
              .onMessage(Answer.class, answer -> settle(replyTo, answer.result()))
              .onMessage(
                  Expired.class,
                  expired ->
                      settle(
                          replyTo,
                          ToolResult.error(
                              "no answer by " + expired.deadline() + "; the call was not made")))
              .build();
        });
  }

  private static Behavior<Command> settle(
      ActorRef<ToolCallActor.Command> replyTo, ToolResult result) {
    replyTo.tell(new ToolCallActor.Ran(result));
    return Behaviors.stopped();
  }

  private static String describe(Throwable failure) {
    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
    String message = cause.getMessage();
    return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
