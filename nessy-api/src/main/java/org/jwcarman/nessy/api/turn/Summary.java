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
package org.jwcarman.nessy.api.turn;

import java.util.Objects;
import org.jwcarman.nessy.api.TurnId;

/**
 * The text shown to a model in place of one chapter's turns.
 *
 * <p><b>Not ambient.</b> Ambient is a view of the world as it stands -- a note, a plan, the time --
 * regenerated on every call and never part of the story: a call's record keeps what it was shown,
 * but no later call reads it back. A summary is the opposite on every count: it is derived from
 * what was actually said, it is durable, and it names exactly which turns it replaces. Letting one
 * stand in for the other would let a summary be quietly dropped like a note, or a note be treated
 * as the record of a conversation.
 *
 * <p>It is written once and never replaced. Several of them cover a long story in successive
 * chapters: the next stretch of turns becomes the next summary, and nothing already written is
 * rewritten or re-costed.
 *
 * @param chapter the closed run of turns this stands in for
 * @param text what to say in place of those turns
 */
public record Summary(Chapter chapter, String text) {

  public Summary {
    Objects.requireNonNull(chapter, "chapter must not be null");
    Objects.requireNonNull(text, "text must not be null");
    if (text.isBlank()) {
      // A summary that says nothing about the turns it replaces has replaced them with silence.
      throw new IllegalArgumentException("a summary must say something");
    }
  }

  /** Whether {@code turn} is one of the turns this stands in for. */
  public boolean covers(TurnId turn) {
    return chapter.covers(turn);
  }
}
