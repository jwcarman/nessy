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

import java.util.List;

/**
 * What is waiting for an agent, and what a policy may do about it.
 *
 * <p>Handed to an {@link BacklogPolicy} when an input arrives, so a strategy says what it wants
 * done rather than rewriting a list. That is what keeps the ordinary cases cheap: keeping
 * everything is an append, keeping only the latest is a replace, and neither reads a row or decodes
 * an input. Only {@link #all()} pays for the backlog, and it is meant to look like it does.
 *
 * <p><b>Operations apply as they are called.</b> A snapshot is of the moment it was asked for, so a
 * strategy that calls {@code all()}, drops something, and calls {@code all()} again sees its own
 * change.
 *
 * <p><b>An ended agent never gets here.</b> Whatever decides to consult a policy checks first
 * whether the agent has been told to end, and refuses the arrival if it has. Anything coalesced
 * into an emptied backlog would be read as work the next time it is asked, which would undo a
 * termination that had already happened.
 *
 * <p><b>It runs with the agent to itself.</b> The transaction that hands this over already holds
 * the agent's row, so nothing else can add to or take from this backlog while a policy is running
 * -- which is what makes a snapshot safe to hold and iterate while changing things, and why these
 * operations need no locking of their own.
 *
 * <p>A handle rather than data, and deliberately small. The three strategies that account for
 * nearly everything -- keep everything, keep only the latest, keep at most N -- are one or two
 * calls each and read nothing. Adding an operation later is easy; taking one away is not.
 *
 * @param <I> the application's input type
 */
public interface Backlog<I> {

  /**
   * Take the one that has waited longest, and remove it.
   *
   * <p>How work leaves a backlog: a turn ends, the agent is idle, and the next thing waiting
   * becomes the next turn. Three answers rather than two, because an agent that has been ended has
   * to say so here -- see {@link Pull.Pill}.
   *
   * <p>Taken under the same lock an arrival is written under, so a turn ending and an input
   * arriving cannot both decide what is at the head. Taking is undone by the transaction, not by
   * putting anything back: a caller that decides against the work rolls back.
   */
  Pull<I> take();

  /** Keep it, behind everything already waiting. Every input matters. */
  void append(BacklogItem<I> item);

  /**
   * Keep it, ahead of everything already waiting.
   *
   * <p>For the arrival that should not wait its turn -- an interrupt, a correction, a cancellation
   * that the things queued behind it would be wasted work against. Costs exactly what appending
   * does: a backlog is ordered by a number, so both ends are a step away from what is already there
   * and nothing in between is renumbered.
   */
  void prepend(BacklogItem<I> item);

  /**
   * Keep only this one.
   *
   * <p>For inputs that are snapshots rather than increments, where an older reading is worthless
   * the moment a newer one exists -- a clock tick, a sensor reading, a resync.
   */
  void replaceAll(BacklogItem<I> item);

  /**
   * How many are waiting, without decoding any of them.
   *
   * <p>What a bounded buffer asks. Counting is not reading: a cap of five is a count, a delete and
   * an insert, and never has to look at an input to enforce itself.
   */
  int size();

  /**
   * Drop the {@code count} oldest.
   *
   * <p>The other half of a bound. Dropping the oldest is the only order anybody has wanted to drop
   * in -- a backlog is a queue, and what falls out of a full one is what has been waiting longest.
   */
  void dropOldest(int count);

  /**
   * Everything waiting, oldest first.
   *
   * <p>The escape hatch, and the only operation that reads the backlog or decodes what is in it.
   * Expiring by age or reordering by priority needs it; appending, keeping only the latest, and
   * holding a bounded buffer do not.
   */
  List<BacklogItem<I>> all();

  /**
   * Replace everything waiting with this, in this order.
   *
   * <p>What {@link #all()} is for: look, decide, and say what the backlog should be instead. An
   * empty list drops everything.
   */
  void rewrite(List<BacklogItem<I>> items);
}
