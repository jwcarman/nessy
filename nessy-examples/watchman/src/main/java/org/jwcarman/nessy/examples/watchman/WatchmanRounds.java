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
package org.jwcarman.nessy.examples.watchman;

import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.spring.boot.PendingApprovalsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clock that drives the application: every half hour, tell the watchman to do its rounds.
 *
 * <p>{@link Harness#observe} is a post, not a call — it returns as soon as the observation is
 * durably the agent's problem, so a round that takes twenty minutes cannot make the next tick late.
 * Twenty ticks queued behind a long round collapse to one, because {@code WatchmanObservations}
 * coalesces them: a watchman that was busy for an hour does one round of catching up, not twenty.
 *
 * <p><b>The first tick is also the recovery.</b> A sharded agent sleeps until something addresses
 * it, and a turn left unfinished by the last shutdown is re-driven by the agent's own recovery once
 * it wakes. So the tick on startup is not just an eager round — it is what gets unfinished work
 * moving again. Without it, that work would wait for the next half-hour boundary.
 */
@Component
public class WatchmanRounds {

  private static final Logger LOG = LoggerFactory.getLogger(WatchmanRounds.class);

  private static final String TICK = "Do your rounds.";

  private final Harness<String> harness;
  private final PendingApprovalsListener listener;

  WatchmanRounds(Harness<String> harness, PendingApprovalsListener listener) {
    this.harness = harness;
    this.listener = listener;
  }

  /**
   * Starts listening, then knocks once.
   *
   * <p>Subscribing BEFORE the first tick matters: the projection only hears what it is present for,
   * and the first round is the one most likely to propose something that needs a person.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void started() {
    harness.subscribe(WatchmanConfiguration.AGENT, listener);
    LOG.info("[watchman] listening, and doing a first round");
    round();
  }

  /**
   * Knocks.
   *
   * <p>The interval is a property because a soak has to see several rounds in the minutes it runs,
   * and half an hour is the right cadence only for the thing actually watching a house. It was
   * hard-coded, and {@code soak.sh} exported a WATCHMAN_CRON that nothing had read since.
   *
   * <p>Logged, and with the words the soak greps for: a run that cannot count its own rounds cannot
   * tell "nothing went wrong" from "nothing happened", which is the failure this soak exists to
   * catch.
   */
  @Scheduled(fixedRateString = "${watchman.round-interval:PT30M}")
  public void round() {
    LOG.info("[watchman] telling the watchman to do its rounds");
    harness.observe(WatchmanConfiguration.AGENT, TICK);
  }
}
