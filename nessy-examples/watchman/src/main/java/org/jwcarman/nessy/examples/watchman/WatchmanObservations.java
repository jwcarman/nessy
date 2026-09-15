package org.jwcarman.nessy.examples.watchman;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.api.ObservationCoalescer;

/** How the watchman's backlog is kept while it is busy. */
public final class WatchmanObservations {

  private static final String TICK = "Do your rounds.";

  /**
   * A tick supersedes every tick already waiting: a watchman busy for an hour does one round of
   * catching up, not twenty. Anything that is not a tick is kept, because a real event must never
   * be swallowed by the next beat of the clock.
   */
  public static final ObservationCoalescer<String> COALESCER =
      (waiting, arriving) -> {
        if (!isTick(arriving)) {
          List<BacklogItem<String>> kept = new ArrayList<>(waiting);
          kept.add(arriving);
          return kept;
        }
        List<BacklogItem<String>> kept = new ArrayList<>(waiting.size() + 1);
        waiting.stream().filter(item -> !isTick(item)).forEach(kept::add);
        kept.add(arriving);
        return kept;
      };

  private WatchmanObservations() {}

  private static boolean isTick(BacklogItem<String> item) {
    return item.observation().endsWith(TICK);
  }
}
