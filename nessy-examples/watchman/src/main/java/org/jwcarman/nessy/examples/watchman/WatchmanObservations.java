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

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.backlog.BacklogItem;
import org.jwcarman.nessy.api.message.UserMessage;

/**
 * The watchman's observation vocabulary, such as it is: a String, because this port has exactly one
 * kind of observation. The coalescing policy belongs HERE rather than at each call site, because it
 * is a property of the observation type: a cron tick is only ever "do your rounds now", so twenty
 * queued ticks are one tick.
 */
public final class WatchmanObservations {

  private static final String TICK = "Do your rounds.";

  /**
   * Anything ending in "Do your rounds." is a tick, and ticks supersede one another: a backlog that
   * already holds one drops it in favour of the newer one, so a watchman that was busy for an hour
   * does twenty minutes of catching up rather than twenty rounds.
   *
   * <p>Written out rather than composed from a key helper, because the coalescer is now a plain
   * function over the waiting list — which is more code here and one less concept to learn.
   */
  public static final BacklogCoalescer<String> COALESCER =
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

  /** The watchman's only observation kind is already text, so rendering it is one message. */
  public static final ObservationRenderer<String> RENDERER = UserMessage::of;

  private WatchmanObservations() {}

  private static boolean isTick(BacklogItem<String> item) {
    return item.observation().endsWith(TICK);
  }
}
