package org.jwcarman.nessy.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One observation waiting to be worked on.
 *
 * <p>Holds the caller's own observation, unrendered. Turning one into content is a renderer's job
 * and happens when a turn opens, not when the observation arrives -- a renderer runs inside the
 * fold, and nothing here does.
 *
 * <p>{@code arrivedAt} is the arriving item's own time rather than a clock read while queuing, so
 * anything that reasons about age is a pure function of what it was given. That is what lets a
 * coalescer expire stale entries without reading a clock, and it is why the field is not called
 * "now".
 *
 * @param <O> the application's observation type
 */
public record BacklogItem<O>(O observation, Instant arrivedAt) {

  public BacklogItem {
    Objects.requireNonNull(observation, "observation must not be null");
    Objects.requireNonNull(arrivedAt, "arrivedAt must not be null");
  }
}
