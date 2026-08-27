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
 * THROWAWAY SPIKE. One approval: the question, the deadline, the answer. Ephemeral.
 *
 * <p>This actor is small enough to look pointless, and it is the clearest illustration of what
 * decomposition buys. Round 1's report ended with "nothing here expires a park — Pekko's timers are
 * per-actor and die with a passivated entity, so deadlines still need Continuum". That was true of
 * a design where the deadline had to be enforced by something OUTSIDE the agent. Here the deadline
 * is enforced by the thing that owns it, in four lines of {@link Behaviors#withTimers}, because
 * there IS a live actor per pending approval and it is the natural owner of its own clock.
 *
 * <p>The caveat is honest and unchanged: this timer is in memory. If the process dies, the deadline
 * dies with it, and the re-spawned approval starts its term afresh. A deadline that must survive a
 * crash still needs a durable timer — either a persistent entity here (see the report's
 * persistent-vs-ephemeral discussion) or something like Continuum. What decomposition removed is
 * the need for that machinery in the COMMON case, not in every case.
 */
public final class ApprovalActor {

  public sealed interface Command {}

  /** The answer, relayed in from the world via the agent and this approval's tool call. */
  public record Answer(boolean approved, String reason) implements Command {}

  /** The term expired. Private: only this actor's own timer sends it. */
  private record Expired() implements Command {}

  private ApprovalActor() {}

  public static Behavior<Command> create(
      String callId, String question, Duration term, ActorRef<ToolCallActor.Command> replyTo) {
    return Behaviors.withTimers(
        timers -> {
          timers.startSingleTimer(new Expired(), term);
          return Behaviors.setup(
              context -> {
                SpikeLifecycleLog.note(callId, "asking: " + question);
                return Behaviors.receive(Command.class)
                    .onMessage(
                        Answer.class,
                        answer -> {
                          replyTo.tell(
                              new ToolCallActor.Answered(answer.approved(), answer.reason()));
                          return Behaviors.stopped();
                        })
                    .onMessage(
                        Expired.class,
                        expired -> {
                          context.getLog().info("[spike] approval {} expired", callId);
                          replyTo.tell(new ToolCallActor.Answered(false, "the approval expired"));
                          return Behaviors.stopped();
                        })
                    .build();
              });
        });
  }
}
