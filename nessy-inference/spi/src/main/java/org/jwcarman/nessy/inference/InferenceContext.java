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
package org.jwcarman.nessy.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.Memory;
import org.jwcarman.nessy.api.State;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;

/**
 * What a provider is given to work from, as strata: the conversation, and whatever stands behind
 * it.
 *
 * <p>There are six, in this order, and each has its own lifetime and its own reason for being
 * there:
 *
 * <ol>
 *   <li><b>instructions</b> -- the system prompt. It stays on the {@link InferenceRequest}, beside
 *       this, because it is fixed when the harness is built and carried on the request, and is the
 *       request's, not the story's.
 *   <li><b>history</b> -- {@link #summaries()}, one per closed chapter, oldest first, and then
 *       {@link #tail()}, the completed turns after them. Written down once and re-sent verbatim.
 *   <li><b>memory</b> -- what was recalled because it bears on this turn. Chosen for the turn being
 *       answered, so it changes most from turn to turn.
 *   <li><b>state</b> -- the agent's standing situation. It changes rarely, and only between turns:
 *       each source answers as of the start of the turn it is handed.
 *   <li><b>the active turn</b> -- {@link #activeTurn()}, the turn being answered, whole: the
 *       question, and every round of calls made so far.
 *   <li><b>ambient</b> -- anything that can change while the agent is working, asked afresh on
 *       every call.
 * </ol>
 *
 * <p>Memory, state and ambient are none of them part of the story. They are asked afresh on every
 * call, and no later call reads an earlier answer back. What a call was shown is kept on that
 * call's record, beside the story and not in it. <b>Where each stratum lands on the wire, and how
 * it is labelled, is the adapter's.</b> This says what each one IS and leaves the placement to the
 * adapter that knows the vendor.
 *
 * <p><b>Turns rather than a flat list of messages</b>, because a flat list is already a wire shape
 * and it is one particular provider's. The same fact is encoded differently by each of them -- a
 * tool result is a {@code user} message carrying {@code tool_result} blocks for one, a message with
 * {@code role: "tool"} for another, and a {@code functionResponse} part for a third. A sequence of
 * {@code (role, content)} pairs has to commit to one of those; a turn commits to none and lets each
 * adapter encode it.
 *
 * <p>So this is the anti-corruption boundary. Everything up to here is ours and portable -- the
 * story is folded into turns once, by logic no provider influences -- and everything past it is one
 * vendor's shape. An adapter translates; it never interprets. Deciding what a refused turn shows
 * the model, or what a failed one says in its place, is a decision taken here, once, rather than
 * reinvented in prose by every adapter that has to render it.
 *
 * <p>Derived per call. Nothing here is part of the story.
 *
 * @param summaries what stands in for the closed chapters, oldest first -- usually empty
 * @param tail the completed turns after the summaries, oldest first
 * @param memory what was recalled for this turn, in the order its sources were bound
 * @param state the agent's standing situation, in the order its sources were bound
 * @param activeTurn the turn being answered
 * @param ambient what can change while the agent works, in the order its sources were bound
 */
public record InferenceContext(
    List<Summary> summaries,
    List<Turn> tail,
    List<Memory> memory,
    List<State> state,
    Turn activeTurn,
    List<Ambient> ambient) {

  public InferenceContext {
    Objects.requireNonNull(summaries, "summaries must not be null");
    Objects.requireNonNull(tail, "tail must not be null");
    Objects.requireNonNull(memory, "memory must not be null");
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(activeTurn, "activeTurn must not be null");
    Objects.requireNonNull(ambient, "ambient must not be null");
    summaries = List.copyOf(summaries);
    tail = List.copyOf(tail);
    memory = List.copyOf(memory);
    state = List.copyOf(state);
    ambient = List.copyOf(ambient);
  }

  /** Turns and background, with nothing compressed away: the last turn is the active one. */
  public InferenceContext(List<Turn> turns, List<Ambient> ambient) {
    this(List.of(), turns, ambient);
  }

  /** Summaries, turns and background: the last turn is the active one. */
  public InferenceContext(List<Summary> summaries, List<Turn> turns, List<Ambient> ambient) {
    this(summaries, tailOf(turns), List.of(), List.of(), activeOf(turns), ambient);
  }

  /** Only turns: the last is the active turn, the rest are the tail. */
  public static InferenceContext of(List<Turn> turns) {
    return new InferenceContext(List.of(), turns, List.of());
  }

  /** The tail and then the active turn: every turn in the context, oldest first. */
  public List<Turn> turns() {
    List<Turn> all = new ArrayList<>(tail.size() + 1);
    all.addAll(tail);
    all.add(activeTurn);
    return List.copyOf(all);
  }

  public boolean hasSummaries() {
    return !summaries.isEmpty();
  }

  public boolean hasAmbient() {
    return !ambient.isEmpty();
  }

  private static Turn activeOf(List<Turn> turns) {
    Objects.requireNonNull(turns, "turns must not be null");
    if (turns.isEmpty()) {
      throw new IllegalArgumentException("a context needs an active turn: turns must not be empty");
    }
    return turns.getLast();
  }

  private static List<Turn> tailOf(List<Turn> turns) {
    activeOf(turns);
    return turns.subList(0, turns.size() - 1);
  }
}
