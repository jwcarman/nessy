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
import org.jwcarman.nessy.api.BacklogPolicy;
import org.jwcarman.nessy.api.ListBacklog;

/**
 * Inputs waiting for an agent that is busy, and the agent's own ending.
 *
 * <p>A small state machine beside the agent's own. This one decides what to hand over; the agent
 * decides what to do with it. Both are pure folds over immutable values, and both live in the same
 * row, so a change to either commits with the other or not at all.
 *
 * <p>Nothing here mutates. Taking an input returns the backlog that would remain, and a caller that
 * decides not to proceed simply never persists it -- which is why refusing costs nothing and needs
 * no compensation.
 *
 * <p>It is stored as one opaque value beside the agent's state, in the same row. Nothing queries
 * inside it -- it is read whole and written whole -- so it earns no columns of its own, and its
 * shape stays a matter for this file rather than for the schema.
 *
 * <p>The discriminators below are a stored format. Every agent that has ever queued anything
 * carries one, so renaming a record here is a migration.
 *
 * @param <I> the application's input type
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Backlog.Open.class, name = "open"),
  @JsonSubTypes.Type(value = Backlog.Sealed.class, name = "sealed")
})
public sealed interface Backlog<I> {

  /**
   * Offers an input, letting the application say what the backlog becomes.
   *
   * <p>The policy arrives as an argument rather than living here because a backlog is stored: it is
   * one opaque value beside the agent's state, and a function has no serialised form. So the policy
   * is supplied at the moment it is applied and gone the instant it returns.
   *
   * <p>Returning {@code this} means the arrival changed nothing, and the fold reads it that way.
   */
  Backlog<I> accept(BacklogItem<I> incoming, BacklogPolicy<I> policy);

  /** What to work on next, if anything. */
  Pull<I> next();

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
  Backlog<I> seal();

  /** How many inputs are waiting. */
  int size();

  /** An empty, accepting backlog. */
  static <I> Backlog<I> empty() {
    return new Open<>(List.of());
  }

  /** Accepting inputs and handing them out in order. */
  record Open<I>(List<BacklogItem<I>> items) implements Backlog<I> {

    public Open {
      items = List.copyOf(items);
    }

    @Override
    public Backlog<I> accept(BacklogItem<I> incoming, BacklogPolicy<I> policy) {
      // The policy operates on a backlog rather than returning one, so it is given a list to
      // operate on and what it leaves behind becomes the next state. Against a database the same
      // calls are statements; here they are an ArrayList, and a policy cannot tell.
      ListBacklog<I> working = new ListBacklog<>(items);
      policy.coalesce(working, incoming);
      return new Open<>(working.items());
    }

    @Override
    public Pull<I> next() {
      return items.isEmpty()
          ? new Pull.Empty<>()
          : new Pull.Item<>(items.getFirst(), new Open<>(items.subList(1, items.size())));
    }

    @Override
    public Backlog<I> seal() {
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
   * -- a sealed backlog offers the pill forever, and termination cannot be undone by a stray input
   * arriving late.
   */
  record Sealed<I>() implements Backlog<I> {

    @Override
    public Backlog<I> accept(BacklogItem<I> incoming, BacklogPolicy<I> policy) {
      // Refused rather than silently swallowed: an input queued here could never be
      // taken, and the caller would have been told it was accepted. The policy is not
      // consulted -- a cap or a merge has no say once nothing more can be taken.
      return this;
    }

    @Override
    public Pull<I> next() {
      return new Pull.Pill<>();
    }

    @Override
    public Backlog<I> seal() {
      return this;
    }

    @Override
    public int size() {
      return 0;
    }
  }
}
