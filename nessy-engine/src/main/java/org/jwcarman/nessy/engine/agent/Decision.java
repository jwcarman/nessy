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
package org.jwcarman.nessy.engine.agent;

import java.util.List;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.engine.history.HistoryEntry;

/**
 * What the fold decided.
 *
 * <p>{@link Ignore} is not "advance to the same state". It means write NOTHING -- no state, no
 * story, no version bump -- which is what a redelivered outcome deserves.
 */
public sealed interface Decision<O> {

  /**
   * @param recorded entries the fold made itself, in order. Everything that needs no renderer: an
   *     answer already arrived as blocks, and a failure or a refusal carries no content at all.
   * @param opening the one thing a fold cannot write down, or null. An observation has to be
   *     rendered before it can be stored, and the renderer belongs to the store -- so the fold says
   *     which observation opened the turn and at what seq, and the store turns it into an entry.
   *     <p><b>It is written after everything in {@code recorded}.</b> A turn closes and the next
   *     opens in one fold, in that order, and nothing else can come between them -- which is why
   *     the ordering can live here rather than in a position within a list.
   */
  record Advance<O>(
      AgentState<O> next,
      List<HistoryEntry> recorded,
      Opening<O> opening,
      List<AgentEffect> effects)
      implements Decision<O> {

    public Advance {
      recorded = List.copyOf(recorded);
      effects = List.copyOf(effects);
    }

    public Advance(AgentState<O> next, List<HistoryEntry> recorded, List<AgentEffect> effects) {
      this(next, recorded, null, effects);
    }

    public boolean opensTurn() {
      return opening != null;
    }
  }

  /**
   * An observation a turn is opening on, and where it sits in the story.
   *
   * <p>Carries the caller's own observation, unrendered -- the last place {@code <O>} appears
   * before the store's renderer ends it. The seq is the fold's to assign and the turn's id is the
   * same number, which is why nothing else needs to be said.
   */
  record Opening<O>(Seq seq, O observation) {}

  record Ignore<O>() implements Decision<O> {}

  static <O> Decision<O> stay(AgentState<O> state) {
    return new Advance<>(state, List.of(), List.of());
  }

  static <O> Decision<O> ignore() {
    return new Ignore<>();
  }
}
