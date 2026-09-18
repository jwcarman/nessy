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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WatchmanRounds {

  private static final Logger LOG = LoggerFactory.getLogger(WatchmanRounds.class);
  private static final String TICK = "Do your rounds.";

  private final Harness<String> harness;

  WatchmanRounds(Harness<String> harness) {
    this.harness = harness;
  }

  // A fixed rate fires as soon as the scheduler starts, so the first round needs no other kick:
  // a second one on ApplicationReadyEvent was two first rounds racing for one agent.
  @Scheduled(fixedRateString = "${watchman.round-interval:PT30M}")
  public void round() {
    LOG.info("[watchman] telling the watchman to do its rounds");
    harness.observe(Watchman.AGENT, TICK);
  }
}
