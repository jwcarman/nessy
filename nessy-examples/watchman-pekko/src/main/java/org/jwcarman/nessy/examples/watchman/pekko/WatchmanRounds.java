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

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clock that drives the application: every half hour, tell the watchman to do its rounds.
 *
 * <p>{@code tell} is a post, not a call — it returns as soon as the message is in the agent's
 * mailbox, so a round that takes twenty minutes cannot make the next cron tick late.
 *
 * <p>The trace starts HERE. {@link Traces#capture} flattens the cron tick's span context into the
 * message, and every actor that handles it re-opens that context as its parent. Without this the
 * round's spans would be a handful of orphans, because nothing propagates a thread-local across a
 * mailbox.
 */
@Component
public class WatchmanRounds {

  private static final Logger LOG = LoggerFactory.getLogger(WatchmanRounds.class);

  /**
   * Every cron tick supersedes any earlier tick still queued: "do your rounds" is not cumulative.
   */
  public static final String ROUNDS = "rounds";

  private final WatchmanActorSystem actors;
  private final StartupSweep sweep;
  private final Transcript transcript;
  private final Traces traces;
  private final Clock clock;

  WatchmanRounds(
      WatchmanActorSystem actors,
      StartupSweep sweep,
      Transcript transcript,
      Traces traces,
      Clock clock) {
    this.actors = actors;
    this.sweep = sweep;
    this.transcript = transcript;
    this.traces = traces;
    this.clock = clock;
  }

  /**
   * The driver obligation, run once the context is up: any round left unfinished by the last
   * shutdown gets its actor back, and that actor's own recovery re-fires whatever it owed.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void recoverUnfinishedRounds() {
    sweep.unfinishedAgents().forEach(agentId -> actors.tell(agentId, new AgentActor.Wake()));
  }

  /** One round. Scheduled in the application; called directly by the tests. */
  @Scheduled(cron = "${watchman.cron:0 */30 * * * *}")
  public void doRounds() {
    traces.inSpan(
        "watchman round",
        java.util.Map.of(),
        () -> {
          String observation = "It is " + clock.instant() + ". Do your rounds.";
          LOG.info("[watchman] telling the watchman: {}", observation);
          // Transcript first, then the agent -- this scheduler thread is not a dispatcher, so the
          // append can block here, and the ordering is guaranteed by being sequential.
          transcript.append(WatchmanGuardian.WATCHMAN, new Turn.User(observation));
          actors.tell(
              WatchmanGuardian.WATCHMAN,
              new AgentActor.Observe(observation, ROUNDS, traces.capture()));
        });
  }
}
