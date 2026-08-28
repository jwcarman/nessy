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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * Runs one tool. Behind a pool router.
 *
 * <p>The router bounds concurrent message PROCESSING, not concurrent in-flight work — this worker
 * hands the command to a virtual thread and returns to its mailbox immediately. For {@code df} and
 * {@code docker ps} that is exactly right. It would be no protection at all for something that must
 * be rate limited, which is why model calls use work pulling instead (see {@link ModelDesk}).
 */
public final class ToolWorker {

  public record RunTool(
      String agentId,
      ToolCallRecord call,
      ActorRef<ToolCallActor.Command> replyTo,
      Map<String, String> trace) {}

  private ToolWorker() {}

  public static Behavior<RunTool> create(
      CommandRunner runner, Memories memories, Executor blocking, Traces traces) {
    return Behaviors.receive(RunTool.class)
        .onMessage(
            RunTool.class,
            message -> {
              ToolCallRecord call = message.call();
              CompletableFuture.supplyAsync(
                      () -> {
                        String result =
                            traces.inSpan(
                                "tool " + call.tool(),
                                message.trace(),
                                () -> {
                                  Traces.attribute("watchman.tool", call.tool());
                                  Traces.attribute("watchman.action", call.action());
                                  return WatchmanTools.run(
                                      runner, call.tool(), call.argumentsJson());
                                });
                        // Remembered first, then the agent is told. Same thread, so the ordering
                        // is not a hope -- and a crash in between leaves an exchange whose
                        // assistant turn the fold will pair up, never a context the model rejects.
                        memories
                            .forAgent(message.agentId())
                            .remember(
                                new org.jwcarman.nessy.spi.Remembrance.ToolExchange(
                                    call.id(),
                                    new org.jwcarman.nessy.api.tool.ToolCall(
                                        call.id(),
                                        call.tool(),
                                        WatchmanTools.argumentsOf(call.argumentsJson())),
                                    org.jwcarman.nessy.api.tool.ToolResult.ok(result)));
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
