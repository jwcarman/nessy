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
package org.jwcarman.nessy.api.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.function.Function;

/**
 * How a waiting backlog is groomed as observations arrive (actor-composition spec §4).
 *
 * <p>A backlog is a thing you <b>groom</b> — items merged, superseded, dropped — which is exactly
 * the set of operations this expresses. It is a plain {@code List}: the agent holds it as state, so
 * a wrapper type around a list bought nothing but a name.
 *
 * <p>Called on every arrival. <b>Coalescing on write is the only place observations become one</b>,
 * which is why a turn takes exactly one item: draining the whole backlog into a single turn would
 * silently override this policy and merge what a vocabulary declined to merge.
 *
 * <p>Implementations must be PURE. The only clock available is {@link BacklogItem#receivedAt()}.
 *
 * @param <O> the observation type
 */
@FunctionalInterface
public interface Coalescer<O> {

  /**
   * The backlog after {@code incoming} arrives — appended, merged into an existing item, or
   * dropped.
   *
   * <p>A merged item must be a NEW {@link BacklogItem} with a fresh id; reusing an id collides the
   * derived {@code Remembrance} key and silently swallows every observation after the first.
   */
  List<BacklogItem<O>> ingest(List<BacklogItem<O>> current, BacklogItem<O> incoming);

  /** Merges nothing: every observation gets its own turn. */
  static <O> Coalescer<O> none() {
    return (current, incoming) -> {
      List<BacklogItem<O>> next = new ArrayList<>(current);
      next.add(incoming);
      return List.copyOf(next);
    };
  }

  /** Supersedes any waiting item sharing a key: the latest wins, the earlier is forgotten. */
  static <O> Coalescer<O> byKey(Function<O, Optional<String>> key) {
    return byKey(key, (existing, incoming) -> incoming);
  }

  /**
   * Merges any waiting item sharing a key, using {@code merge} to combine the observations.
   *
   * <p>The survivor keeps the EARLIER item's {@code receivedAt} — its queue position stays honest,
   * and a busy topic cannot look eternally fresh to a staleness policy (spec §9.1).
   */
  static <O> Coalescer<O> byKey(Function<O, Optional<String>> key, BinaryOperator<O> merge) {
    return (current, incoming) -> {
      Optional<String> incomingKey = key.apply(incoming.observation());
      if (incomingKey.isEmpty()) {
        List<BacklogItem<O>> next = new ArrayList<>(current);
        next.add(incoming);
        return List.copyOf(next);
      }
      String k = incomingKey.get();
      List<BacklogItem<O>> next = new ArrayList<>(current.size() + 1);
      boolean merged = false;
      for (BacklogItem<O> item : current) {
        if (!merged && key.apply(item.observation()).filter(k::equals).isPresent()) {
          next.add(
              new BacklogItem<>(
                  incoming.id(),
                  merge.apply(item.observation(), incoming.observation()),
                  item.receivedAt()));
          merged = true;
        } else {
          next.add(item);
        }
      }
      if (!merged) {
        next.add(incoming);
      }
      return List.copyOf(next);
    };
  }
}
