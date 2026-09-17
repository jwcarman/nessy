package org.jwcarman.nessy.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.function.Function;

/**
 * Decides what a backlog looks like after an observation arrives.
 *
 * <p>Some observations are increments and every one matters -- two questions from a person deserve
 * two answers. Others are snapshots, where an older reading is worthless the moment a newer one
 * exists, and keeping forty of them means the agent reasons about a world that no longer holds.
 * Which of those an observation is, only the application knows.
 *
 * <p>It returns the whole backlog rather than a verdict on the incoming item, because the useful
 * strategies are not all local: a full resync supersedes everything pending, a cap drops the
 * oldest, a priority rule reorders. That generality is affordable only because the backlog is
 * already in hand -- it was decoded to make any decision at all -- so a whole-list rewrite costs
 * the same as replacing one entry.
 *
 * <p><b>Implementations must be pure.</b> This runs inside the fold, under the agent's row lock: no
 * I/O, no clock, no randomness. Anything time-dependent uses {@code incoming.arrivedAt()} as now,
 * which is why that field is the arriving item's own time rather than a clock read while queuing.
 *
 * <p><b>Returning the backlog unchanged means nothing happened</b>, and the fold treats it that way
 * -- no state written, no version bumped. A coalescer that drops an arrival is not making a small
 * change, it is making none.
 *
 * <p>Only ever consulted by a backlog that is still accepting. A sealed one refuses regardless, so
 * a cap or a merge has no say during termination.
 *
 * @param <O> the application's observation type
 */
@FunctionalInterface
public interface ObservationCoalescer<O> {

  /**
   * @param backlog what is already waiting, oldest first
   * @param incoming what just arrived
   * @return the backlog that replaces it; may drop {@code incoming}, and may drop or reorder
   *     anything already there
   */
  List<BacklogItem<O>> coalesce(List<BacklogItem<O>> backlog, BacklogItem<O> incoming);

  /** Every observation matters. The right answer for anything a person said. */
  static <O> ObservationCoalescer<O> keepAll() {
    return ObservationCoalescer::append;
  }

  /**
   * Only the newest observation matters. Anything still waiting is superseded by the arrival, so
   * the backlog never holds more than one -- the right answer for a clock tick, where an agent
   * catching up should do one round, not every one it missed.
   */
  static <O> ObservationCoalescer<O> keepLatest() {
    return (backlog, incoming) -> List.of(incoming);
  }

  /**
   * Keeps only the newest observation per key, <em>in the position the first one held</em>.
   *
   * <p>Position matters: moving a refreshed entry to the back lets a fast-updating sensor push
   * itself ahead of everything else forever, and whatever was queued behind it never gets taken.
   */
  static <O, K> ObservationCoalescer<O> replaceBy(Function<? super O, K> key) {
    return (backlog, incoming) -> {
      K incomingKey = key.apply(incoming.observation());
      List<BacklogItem<O>> next = new ArrayList<>(backlog);
      for (int i = 0; i < next.size(); i++) {
        if (incomingKey.equals(key.apply(next.get(i).observation()))) {
          next.set(i, incoming);
          return List.copyOf(next);
        }
      }
      next.add(incoming);
      return List.copyOf(next);
    };
  }

  /**
   * Keeps the first observation per key and discards later ones. For a signal that says "go and
   * look", where a second is redundant until the first has been acted upon.
   */
  static <O, K> ObservationCoalescer<O> dropRepeats(Function<? super O, K> key) {
    return (backlog, incoming) -> {
      K incomingKey = key.apply(incoming.observation());
      boolean present =
          backlog.stream().anyMatch(item -> incomingKey.equals(key.apply(item.observation())));
      return present ? List.copyOf(backlog) : append(backlog, incoming);
    };
  }

  /** Combines an arrival with the pending observation sharing its key, in place. */
  static <O, K> ObservationCoalescer<O> mergeBy(
      Function<? super O, K> key, BinaryOperator<O> merge) {
    return (backlog, incoming) -> {
      K incomingKey = key.apply(incoming.observation());
      List<BacklogItem<O>> next = new ArrayList<>(backlog);
      for (int i = 0; i < next.size(); i++) {
        BacklogItem<O> existing = next.get(i);
        if (incomingKey.equals(key.apply(existing.observation()))) {
          next.set(
              i,
              new BacklogItem<>(
                  merge.apply(existing.observation(), incoming.observation()),
                  existing.arrivedAt()));
          return List.copyOf(next);
        }
      }
      next.add(incoming);
      return List.copyOf(next);
    };
  }

  /**
   * Drops anything older than {@code ttl}, measured against the arriving observation rather than a
   * clock -- so this stays pure, and stale entries are cleared by the next arrival rather than by
   * anything watching.
   */
  default ObservationCoalescer<O> expiring(Duration ttl) {
    return (backlog, incoming) -> {
      Instant floor = incoming.arrivedAt().minus(ttl);
      List<BacklogItem<O>> fresh =
          backlog.stream().filter(item -> !item.arrivedAt().isBefore(floor)).toList();
      return coalesce(fresh, incoming);
    };
  }

  /**
   * Bounds the backlog by discarding the oldest entries.
   *
   * <p>A cap is what turns a missing coalesce key from a slow leak into a bounded loss. Dropping
   * the oldest is one answer; refusing the arrival so the caller is told is the other, and that one
   * belongs at the door where there is somebody to tell.
   */
  default ObservationCoalescer<O> capped(int max) {
    return (backlog, incoming) -> {
      List<BacklogItem<O>> next = coalesce(backlog, incoming);
      return next.size() <= max ? next : List.copyOf(next.subList(next.size() - max, next.size()));
    };
  }

  private static <O> List<BacklogItem<O>> append(
      List<BacklogItem<O>> backlog, BacklogItem<O> incoming) {
    List<BacklogItem<O>> next = new ArrayList<>(backlog);
    next.add(incoming);
    return List.copyOf(next);
  }
}
