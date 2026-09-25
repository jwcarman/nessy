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

package org.jwcarman.nessy.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.function.Function;

/**
 * What happens to a backlog when an observation arrives.
 *
 * <p>Some observations are increments and every one matters -- two questions from a person deserve
 * two answers. Others are snapshots, where an older reading is worthless the moment a newer one
 * exists, and keeping forty of them means the agent reasons about a world that no longer holds.
 * Which of those an observation is, only the application knows.
 *
 * <p><b>It says what to do rather than handing back a list.</b> The cases that account for nearly
 * everything -- keep them all, keep only the latest, keep at most N -- are one or two calls on
 * {@link Backlog} and never read a row or decode an observation. Only {@link Backlog#all()} pays
 * for the backlog, and the strategies that need it say so at the call site.
 *
 * <p><b>It runs with the agent to itself.</b> The transaction that calls it holds the agent's row,
 * so nothing else can add to or take from this backlog while it runs. Its side effects are exactly
 * the operations on {@link Backlog} and nothing else: no I/O, no clock, no randomness. Anything
 * time-dependent uses {@code incoming.arrivedAt()} as now, which is why that field is the arriving
 * item's own time rather than a clock read while queuing.
 *
 * <p><b>Never consulted for an agent that has ended.</b> An arrival that got past that check would
 * be put into an emptied backlog and read as work the next time it was asked, undoing a termination
 * that had already happened.
 *
 * @param <O> the application's observation type
 */
@FunctionalInterface
public interface BacklogPolicy<O> {

  /**
   * @param backlog what is already waiting, and what may be done about it
   * @param incoming what just arrived
   */
  void coalesce(Backlog<O> backlog, BacklogItem<O> incoming);

  /** Every observation matters. The right answer for anything a person said. */
  static <O> BacklogPolicy<O> keepAll() {
    return (backlog, incoming) -> backlog.append(incoming);
  }

  /**
   * Only the newest observation matters. Anything still waiting is superseded by the arrival, so
   * the backlog never holds more than one -- the right answer for a clock tick, where an agent
   * catching up should do one round, not every one it missed.
   */
  static <O> BacklogPolicy<O> keepLatest() {
    return (backlog, incoming) -> backlog.replaceAll(incoming);
  }

  /**
   * Keeps at most {@code max}, discarding the ones that have waited longest.
   *
   * <p>A bounded buffer, and the only one of these that bounds anything: {@link #keepAll()} grows
   * for as long as an agent is behind. Counting is not reading -- this never decodes an observation
   * to enforce itself.
   */
  static <O> BacklogPolicy<O> bounded(int max) {
    if (max < 1) {
      throw new IllegalArgumentException("a bound of " + max + " would keep nothing");
    }
    return (backlog, incoming) -> {
      int waiting = backlog.size();
      if (waiting >= max) {
        backlog.dropOldest(waiting - max + 1);
      }
      backlog.append(incoming);
    };
  }

  /**
   * Keeps only the newest observation per key, <em>in the position the first one held</em>.
   *
   * <p>Position matters: moving a refreshed entry to the back lets a fast-updating sensor push
   * itself ahead of everything else forever, and whatever was queued behind it never gets taken.
   *
   * <p>Reads the whole backlog, because a key is not something the backlog is ordered by. That is
   * the price of coalescing this way and it is paid on every arrival.
   */
  static <O, K> BacklogPolicy<O> replaceBy(Function<? super O, K> key) {
    return (backlog, incoming) -> inPlace(backlog, incoming, key, (existing, arriving) -> arriving);
  }

  /**
   * Keeps the first observation per key and discards later ones. For a signal that says "go and
   * look", where a second is redundant until the first has been acted upon.
   *
   * <p>Reads the whole backlog, for the same reason as {@link #replaceBy}.
   */
  static <O, K> BacklogPolicy<O> dropRepeats(Function<? super O, K> key) {
    return (backlog, incoming) -> {
      K arriving = key.apply(incoming.observation());
      boolean present =
          backlog.all().stream().anyMatch(item -> arriving.equals(key.apply(item.observation())));
      if (!present) {
        backlog.append(incoming);
      }
    };
  }

  /**
   * Combines an arrival with the pending observation sharing its key, in place.
   *
   * <p>Reads the whole backlog, for the same reason as {@link #replaceBy}.
   */
  static <O, K> BacklogPolicy<O> mergeBy(Function<? super O, K> key, BinaryOperator<O> merge) {
    return (backlog, incoming) ->
        inPlace(
            backlog,
            incoming,
            key,
            (existing, arriving) ->
                new BacklogItem<>(
                    merge.apply(existing.observation(), arriving.observation()),
                    existing.arrivedAt()));
  }

  /**
   * This policy, having first dropped anything older than {@code ttl}.
   *
   * <p>Measured against the arriving observation rather than a clock, so this stays a function of
   * what it was given, and stale entries are cleared by the next arrival rather than by anything
   * watching.
   *
   * <p>Reads the whole backlog: age is not something it is indexed by.
   */
  default BacklogPolicy<O> expiring(Duration ttl) {
    return (backlog, incoming) -> {
      Instant floor = incoming.arrivedAt().minus(ttl);
      List<BacklogItem<O>> fresh =
          backlog.all().stream().filter(item -> !item.arrivedAt().isBefore(floor)).toList();
      if (fresh.size() != backlog.size()) {
        backlog.rewrite(fresh);
      }
      coalesce(backlog, incoming);
    };
  }

  /**
   * This policy, bounded to {@code max} afterwards by dropping the oldest.
   *
   * <p>What turns a policy that can grow -- a missing key, a burst -- from a slow leak into a
   * bounded loss.
   */
  default BacklogPolicy<O> capped(int max) {
    if (max < 1) {
      throw new IllegalArgumentException("a cap of " + max + " would keep nothing");
    }
    return (backlog, incoming) -> {
      coalesce(backlog, incoming);
      int waiting = backlog.size();
      if (waiting > max) {
        backlog.dropOldest(waiting - max);
      }
    };
  }

  /** Find by key and replace in place, or append when nothing matches. */
  private static <O, K> void inPlace(
      Backlog<O> backlog,
      BacklogItem<O> incoming,
      Function<? super O, K> key,
      BinaryOperator<BacklogItem<O>> combine) {
    K arriving = key.apply(incoming.observation());
    List<BacklogItem<O>> waiting = backlog.all();
    List<BacklogItem<O>> next = new ArrayList<>(waiting);
    for (int i = 0; i < next.size(); i++) {
      if (arriving.equals(key.apply(next.get(i).observation()))) {
        next.set(i, combine.apply(next.get(i), incoming));
        backlog.rewrite(next);
        return;
      }
    }
    backlog.append(incoming);
  }
}
