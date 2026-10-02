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
 * Says where an agent's history is cut into chapters.
 *
 * <p>Asked when a turn ends, off the agent's own thread, so it may block and may call a model. It
 * is shown the turns that are still open and answers with the ones that each end a chapter, in
 * order. An empty answer closes nothing.
 *
 * <p><b>It may answer behind the newest turn.</b> Naming the fourth of six open turns closes a
 * chapter over the first four and leaves two open. It may also name several turns, closing several
 * chapters at once, which is what an agent with a long run of open turns needs.
 *
 * <p>The chapters themselves are made by the engine from the answer: the first runs from the oldest
 * open turn through the first end, the next from the turn after that through the second, and so on.
 * An answer naming a turn that is not open, or out of order, closes nothing.
 */
@FunctionalInterface
public interface ChapterPolicy {

  /**
   * @param open the completed turns not yet in a chapter, oldest first
   * @return the turns that each end a chapter, in order; empty when nothing closes yet
   */
  List<TurnId> ends(OpenTurns open);

  /**
   * A chapter every {@code turns} turns: closes the oldest {@code turns} open turns once there are
   * that many.
   */
  static ChapterPolicy every(int turns) {
    if (turns < 1) {
      throw new IllegalArgumentException("a chapter holds at least one turn: " + turns);
    }
    return open -> open.turns().size() < turns ? List.of() : List.of(open.turns().get(turns - 1));
  }
}
