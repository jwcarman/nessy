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
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * One pending approval: the question, the clock, and nothing else. Ephemeral child of a {@link
 * ToolCallActor}.
 *
 * <p><b>This is the answer to "does Continuum survive?" for the approval half.</b> Continuum
 * existed to hold a question while nobody was waiting for it, and to fire when a term expired. Here
 * there IS something waiting — this actor — and the term is its own timer. No computation id is
 * minted, no outbox row is written, and no completion desk is consulted: a human's answer arrives
 * at the agent by id, is persisted there, and is relayed down to this actor.
 *
 * <p><b>The deadline survives a restart</b>, which is the part that looks like it should need
 * durable timers and does not. The term is not stored; the ASK TIME is ({@link
 * ToolCallRecord#askedAt}), and the remaining time is recomputed from it every time this actor is
 * spawned. A process that is down for an hour comes back with an hour less on the clock, and a term
 * that expired while the process was down fires immediately on recovery.
 */
public final class ApprovalActor {

  public sealed interface Command {}

  /** The answer, relayed in from the world via the agent and this approval's tool call. */
  public record Answer(boolean approved, String by, String note) implements Command {}

  /** The term ran out. Private: only this actor's own timer sends it. */
  private record Expired() implements Command {}

  private ApprovalActor() {}

  public static Behavior<Command> create(
      ToolCallRecord call, Duration term, Instant now, ActorRef<ToolCallActor.Command> replyTo) {

    // Re-armed, not restarted. What is left of the term is measured from when the question was
    // asked, which is a persisted fact, so a crash cannot silently extend a deadline.
    Duration remaining = term.minus(Duration.between(call.askedAt(), now));
    Duration wait = remaining.isNegative() ? Duration.ZERO : remaining;

    return Behaviors.withTimers(
        timers -> {
          timers.startSingleTimer(new Expired(), wait);
          return Behaviors.setup(
              context -> {
                context
                    .getLog()
                    .info(
                        "[watchman] approval pending for {} ({}), {} left",
                        call.id(),
                        call.action(),
                        wait);
                return Behaviors.receive(Command.class)
                    .onMessage(
                        Answer.class,
                        answer -> {
                          replyTo.tell(
                              new ToolCallActor.Answered(
                                  answer.approved(), answer.by(), answer.note()));
                          return Behaviors.stopped();
                        })
                    .onMessage(
                        Expired.class,
                        expired -> {
                          replyTo.tell(
                              new ToolCallActor.Answered(
                                  false, "watchman", "nobody answered within " + term));
                          return Behaviors.stopped();
                        })
                    .build();
              });
        });
  }
}
