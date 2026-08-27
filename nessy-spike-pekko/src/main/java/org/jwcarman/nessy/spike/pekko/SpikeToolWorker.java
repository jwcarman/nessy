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

import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * THROWAWAY SPIKE. Cheap local tools, behind a plain pool router.
 *
 * <p>A pool router is the right shape here and the WRONG shape for model calls, for a reason worth
 * stating because it is not obvious: <b>a pool router bounds concurrent message PROCESSING, not
 * concurrent in-flight work.</b> This worker hands the tool to a virtual thread and returns to its
 * mailbox immediately, so a pool of four will happily start four hundred overlapping tool
 * executions. The pool caps how many messages are being dispatched at once, which for work that
 * completes in microseconds is all anyone wanted.
 *
 * <p>When the in-flight count is the thing that must be bounded — a model call costing money, or a
 * provider rate limit — this shape gives no protection at all, and that is exactly why {@link
 * SpikeModelDesk} uses work pulling instead: its consumer does not ask for the next job until it
 * has confirmed the current one, so "one in flight per worker" is structural rather than hoped for.
 */
public final class SpikeToolWorker {

  /** The only message: run this call and tell the caller. */
  public record RunTool(SpikeToolCall call, ActorRef<ToolCallActor.Command> replyTo) {}

  private SpikeToolWorker() {}

  public static Behavior<RunTool> create(SpikeToolbox toolbox, Executor blocking) {
    return Behaviors.receive(RunTool.class)
        .onMessage(
            RunTool.class,
            message -> {
              // Off the dispatcher immediately: the actor's thread never waits on the tool.
              toolbox
                  .run(message.call(), blocking)
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
