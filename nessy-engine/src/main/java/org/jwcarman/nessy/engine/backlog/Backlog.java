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
package org.jwcarman.nessy.engine.backlog;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.api.ObservationCoalescer;

/**
 * Observations waiting for an agent that is busy, and the agent's own ending.
 *
 * <p>A small state machine beside the agent's own. This one decides what to hand over; the agent
 * decides what to do with it. Both are pure folds over immutable values, and both live in the same
 * row, so a change to either commits with the other or not at all.
 *
 * <p>Nothing here mutates. Taking an observation returns the backlog that would remain, and a
 * caller that decides not to proceed simply never persists it -- which is why refusing costs
 * nothing and needs no compensation.
 *
 * <p>It is stored as one opaque value beside the agent's state, in the same row. Nothing queries
 * inside it -- it is read whole and written whole -- so it earns no columns of its own, and its
 * shape stays a matter for this file rather than for the schema.
 *
 * <p>The discriminators below are a stored format. Every agent that has ever queued anything
 * carries one, so renaming a record here is a migration.
 *
 * @param <O> the application's observation type
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Backlog.Open.class, name = "open"),
  @JsonSubTypes.Type(value = Backlog.Sealed.class, name = "sealed")
})
public sealed interface Backlog<O> {

  /**
   * Offers an observation, letting the application say what the backlog becomes.
   *
   * <p>The coalescer arrives as an argument rather than living here because a backlog is stored: it
   * is one opaque value beside the agent's state, and a function has no serialised form. So the
   * policy is supplied at the moment it is applied and gone the instant it returns.
   *
   * <p>Returning {@code this} means the arrival changed nothing, and the fold reads it that way.
   */
  Backlog<O> accept(BacklogItem<O> incoming, ObservationCoalescer<O> coalescer);

  /** What to work on next, if anything. */
  Pull<O> next();

  /**
   * Ends the agent's intake, abandoning whatever was waiting.
   *
   * <p>No option to drain. Terminating is the application saying it is done, and finishing a queue
   * afterwards means calling models for answers nobody will read. It would also double every
   * reading of this type: "sealed" and "sealed but still working" behave differently, and the
   * second is a state nobody asked for.
   *
   * <p>What cannot be abandoned is a turn already in flight -- its effect row exists and is owed an
   * outcome. Termination waits for that one, and no longer.
   */
  Backlog<O> seal();

  /** How many observations are waiting. */
  int size();

  /** An empty, accepting backlog. */
  static <O> Backlog<O> empty() {
    return new Open<>(List.of());
  }

  /** Accepting observations and handing them out in order. */
  record Open<O>(List<BacklogItem<O>> items) implements Backlog<O> {

    public Open {
      items = List.copyOf(items);
    }

    @Override
    public Backlog<O> accept(BacklogItem<O> incoming, ObservationCoalescer<O> coalescer) {
      return new Open<>(coalescer.coalesce(items, incoming));
    }

    @Override
    public Pull<O> next() {
      return items.isEmpty()
          ? new Pull.Empty<>()
          : new Pull.Item<>(items.getFirst(), new Open<>(items.subList(1, items.size())));
    }

    @Override
    public Backlog<O> seal() {
      return new Sealed<>();
    }

    @Override
    public int size() {
      return items.size();
    }
  }

  /**
   * Taking nothing more, and offering nothing more.
   *
   * <p>Carries no items: terminating abandons what was waiting. So {@link #next()} is unconditional
   * -- a sealed backlog offers the pill forever, and termination cannot be undone by a stray
   * observation arriving late.
   */
  record Sealed<O>() implements Backlog<O> {

    @Override
    public Backlog<O> accept(BacklogItem<O> incoming, ObservationCoalescer<O> coalescer) {
      // Refused rather than silently swallowed: an observation queued here could never be
      // taken, and the caller would have been told it was accepted. The coalescer is not
      // consulted -- a cap or a merge has no say once nothing more can be taken.
      return this;
    }

    @Override
    public Pull<O> next() {
      return new Pull.Pill<>();
    }

    @Override
    public Backlog<O> seal() {
      return this;
    }

    @Override
    public int size() {
      return 0;
    }
  }
}
