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

import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.message.TextBlock;

/**
 * The watchman's observation vocabulary, such as it is: a String, because this port has exactly one
 * kind of observation. The coalescing policy belongs HERE rather than at each call site, because it
 * is a property of the observation type: a cron tick is only ever "do your rounds now", so twenty
 * queued ticks are one tick.
 */
public final class WatchmanObservations {

  private static final String ROUNDS = "rounds";

  /** Anything ending in "Do your rounds." is a tick, and ticks supersede one another. */
  public static final Coalescer<String> COALESCER =
      Coalescer.byKey(
          text -> text.endsWith("Do your rounds.") ? Optional.of(ROUNDS) : Optional.empty());

  /** The watchman's only observation kind is already text, so rendering it is a single block. */
  public static final ObservationRenderer<String> RENDERER =
      observation -> List.of(new TextBlock(observation));

  private WatchmanObservations() {}
}
