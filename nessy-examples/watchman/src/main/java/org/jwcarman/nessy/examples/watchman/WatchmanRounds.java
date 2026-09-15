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
