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

import java.time.Clock;
import java.util.Objects;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Harness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clock that drives the whole application (spec §2): every half hour by default, tell the one
 * agent to do its rounds.
 *
 * <p>{@code tell} is a post, not a call. It returns as soon as the observation is on the agent's
 * backlog, and the round itself happens on the harness's own executor — which is why a round that
 * takes twenty minutes cannot make the next cron tick late, and why this method has nothing to
 * return.
 *
 * <p>The prompt carries the time. A long-running agent has memory of previous rounds and no
 * independent sense of when it is, so "It is 2026-08-26T14:30:00Z" is the difference between "the
 * disk filled up" and "the disk has been full for six hours".
 */
@Component
public class Rounds {

  /** The one agent. One box, one watchman. */
  public static final AgentId WATCHMAN = AgentId.of("watchman");

  private static final Logger LOG = LoggerFactory.getLogger(Rounds.class);

  private final Harness<String> harness;
  private final Clock clock;

  Rounds(Harness<String> harness) {
    this.harness = Objects.requireNonNull(harness, "harness must not be null");
    this.clock = Clock.systemUTC();
  }

  /** One round. Scheduled in the application; called directly by the tests. */
  @Scheduled(cron = "${watchman.cron:0 */30 * * * *}")
  public void doRounds() {
    String observation = "It is " + clock.instant() + ". Do your rounds.";
    LOG.info("telling the watchman: {}", observation);
    harness.bind(WATCHMAN).tell(observation);
  }
}
