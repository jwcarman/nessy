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

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.Remembrance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one tool. Behind a pool router.
 *
 * <p>The router bounds concurrent message PROCESSING, not concurrent in-flight work — this worker
 * hands the command to a virtual thread and returns to its mailbox immediately. For {@code df} and
 * {@code docker ps} that is exactly right. It would be no protection at all for something that must
 * be rate limited, which is why model calls use work pulling instead (see {@link ModelDesk}).
 *
 * <p>The model's arguments never travel with the message: {@link RunTool} carries only the claim
 * id, and this worker is the one place that resolves it, on the same virtual thread that runs the
 * tool. That keeps the argument text out of the actor mailbox and out of every trace attribute that
 * touches {@code RunTool} — it exists for exactly as long as this call takes.
 *
 * <p><b>{@link ToolCallActor.Ran} is told ONLY when an exchange was actually recorded.</b> A thrown
 * tool becomes a {@link ToolResult#error}, recorded like any other outcome -- that keeps {@code
 * isError} doing its documented job. But {@code remember} itself can throw (a substrate problem:
 * connectivity, serialization) or the submission to {@code blocking} can be rejected (the executor
 * shutting down); either failure means nothing committed, per {@code Memory}'s Law 1 ("append
 * before commit"). Telling the actor {@code Ran} anyway would settle a call whose exchange the fold
 * can never pair an assistant turn against -- exactly the bug this worker exists to not have. So on
 * either failure this worker says nothing: the call stays un-settled, and a respawn (the same
 * recovery path a mid-run crash already relies on) retries it from scratch, rather than a false
 * settle standing in for a retry the actor protocol has no message for.
 */
public final class ToolWorker {

  private static final Logger LOG = LoggerFactory.getLogger(ToolWorker.class);

  public record RunTool(
      String agentId,
      String turnId,
      ToolCallRecord call,
      String argumentsClaimId,
      ActorRef<ToolCallActor.Command> replyTo,
      Map<String, String> trace) {}

  private ToolWorker() {}

  public static Behavior<RunTool> create(
      CommandRunner runner, Memories memories, Executor blocking, Traces traces, Claims claims) {
    return Behaviors.receive(RunTool.class)
        .onMessage(
            RunTool.class,
            message -> {
              try {
                CompletableFuture.supplyAsync(
                        () -> runAndRemember(message, runner, memories, traces, claims), blocking)
                    .whenComplete(
                        (result, failure) -> {
                          if (failure == null) {
                            message.replyTo().tell(new ToolCallActor.Ran(result));
                          } else {
                            LOG.warn(
                                "call {} not settled -- its exchange was never recorded: {}",
                                message.call().id(),
                                describe(failure));
                          }
                        });
              } catch (RuntimeException rejected) {
                // The executor rejected submission outright (e.g. mid-shutdown): the same rule
                // applies -- no commit happened, so no Ran() is told.
                LOG.warn(
                    "call {} not settled -- could not even be submitted: {}",
                    message.call().id(),
                    describe(rejected));
              }
              return Behaviors.same();
            })
        .build();
  }

  /**
   * Runs the tool and remembers its exchange, on the virtual thread {@code blocking} hands us.
   * Remembered first, then the agent is told (by the caller, once this returns) -- same thread, so
   * the ordering is not a hope, and a crash in between leaves an exchange whose assistant turn the
   * fold will pair up, never a context the model rejects.
   *
   * <p>A thrown claim lookup or tool run becomes {@link ToolResult#error}, recorded like any other
   * outcome. A thrown {@code remember} is NOT caught here -- it propagates so the caller can tell
   * the difference between "the tool failed, but its failure is on the record" and "nothing is on
   * the record at all."
   */
  private static ToolResult runAndRemember(
      RunTool message, CommandRunner runner, Memories memories, Traces traces, Claims claims) {
    ToolCallRecord call = message.call();
    String arguments;
    try {
      arguments =
          claims
              .get(message.agentId(), message.turnId(), message.argumentsClaimId())
              .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
              .orElseThrow(
                  () -> new IllegalStateException("no claim for " + message.argumentsClaimId()));
    } catch (RuntimeException e) {
      ToolResult result = ToolResult.error("tool arguments could not be resolved: " + describe(e));
      remember(memories, message.agentId(), call, WatchmanTools.argumentsOf("{}"), result);
      return result;
    }
    ToolResult result;
    try {
      // GenAI semconv names this span `execute_tool` and carries the tool name as an attribute
      // rather than baking it into the span name -- a name per tool would blow up cardinality in
      // every backend.
      result =
          ToolResult.ok(
              traces.inSpan(
                  "execute_tool",
                  message.trace(),
                  () -> {
                    traces.tag("nessy.agent.id", message.agentId());
                    traces.tag("nessy.tool.call.id", call.id());
                    traces.tag("gen_ai.tool.name", call.tool());
                    traces.tag("watchman.action", call.action());
                    return WatchmanTools.run(runner, call.tool(), arguments);
                  }));
    } catch (RuntimeException e) {
      result = ToolResult.error(describe(e));
    }
    remember(memories, message.agentId(), call, WatchmanTools.argumentsOf(arguments), result);
    return result;
  }

  /**
   * Package-visible so {@link ToolCallActor#settleAsDenied} can record a denial's exchange in
   * exactly the same shape as a run's outcome -- one recording routine, two callers.
   */
  static void remember(
      Memories memories,
      String agentId,
      ToolCallRecord call,
      JsonNode arguments,
      ToolResult result) {
    memories
        .forAgent(agentId)
        .remember(
            new Remembrance.ToolExchange(
                call.id(), new ToolCall(call.id(), call.tool(), arguments), result));
  }

  /** An exception message with a class name, never bare {@code "null"} for a message-less one. */
  static String describe(Throwable e) {
    String message = e.getMessage();
    return e.getClass().getSimpleName()
        + (message == null || message.isBlank() ? "" : ": " + message);
  }
}
