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
 */
public final class ToolWorker {

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
              ToolCallRecord call = message.call();
              CompletableFuture.supplyAsync(
                      () -> {
                        String arguments =
                            claims
                                .get(
                                    message.agentId(), message.turnId(), message.argumentsClaimId())
                                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                                .orElseThrow(
                                    () ->
                                        new IllegalStateException(
                                            "no claim for " + message.argumentsClaimId()));
                        // GenAI semconv names this span `execute_tool` and carries the tool name
                        // as an attribute rather than baking it into the span name -- a name per
                        // tool would blow up cardinality in every backend.
                        String result =
                            traces.inSpan(
                                "execute_tool",
                                message.trace(),
                                () -> {
                                  traces.tag("nessy.agent.id", message.agentId());
                                  traces.tag("nessy.tool.call.id", call.id());
                                  traces.tag("gen_ai.tool.name", call.tool());
                                  traces.tag("watchman.action", call.action());
                                  return WatchmanTools.run(runner, call.tool(), arguments);
                                });
                        // Remembered first, then the agent is told. Same thread, so the ordering
                        // is not a hope -- and a crash in between leaves an exchange whose
                        // assistant turn the fold will pair up, never a context the model rejects.
                        memories
                            .forAgent(message.agentId())
                            .remember(
                                new Remembrance.ToolExchange(
                                    call.id(),
                                    new ToolCall(
                                        call.id(),
                                        call.tool(),
                                        WatchmanTools.argumentsOf(arguments)),
                                    ToolResult.ok(result)));
                        return result;
                      },
                      blocking)
                  .whenComplete(
                      (result, failure) ->
                          message
                              .replyTo()
                              .tell(
                                  new ToolCallActor.Ran(
                                      failure == null ? result : "failed: " + failure)));
              return Behaviors.same();
            })
        .build();
  }
}
