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

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;

/**
 * What stands in for a run of turns that are no longer sent whole.
 *
 * <p><b>Not ambient.</b> Ambient is a view of the world as it stands -- a note, a plan, the time --
 * regenerated on every call and never written down. A summary is the opposite on every count: it is
 * derived from what was actually said, it is durable, and it names exactly which turns it replaces.
 * Letting one stand in for the other would let a summary be quietly dropped like a note, or a note
 * be treated as the record of a conversation.
 *
 * <p><b>The range is whole turns.</b> A turn id is the seq of the observation that opened it, so
 * {@code from} and {@code through} sit on the same number line as every entry -- and because they
 * are turn ids rather than arbitrary seqs, a summary can never split a turn, leaving a reply whose
 * question was compressed away.
 *
 * <p>Several of them may cover a long story in successive ranges. That is what makes folding cheap
 * and append-only: the next stretch of turns becomes the next summary, and nothing already written
 * is rewritten or re-costed.
 *
 * @param from the first turn this covers
 * @param through the last turn this covers, inclusive
 * @param content what to say in place of those turns
 */
public record Summary(TurnId from, TurnId through, List<Block.SummaryContent> content) {

  public Summary {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(through, "through must not be null");
    Objects.requireNonNull(content, "content must not be null");
    if (through.value() < from.value()) {
      throw new IllegalArgumentException(
          "a summary must run forwards: from %s through %s".formatted(from, through));
    }
    if (content.isEmpty()) {
      // A summary that says nothing about the turns it replaces has replaced them with silence.
      throw new IllegalArgumentException("a summary must say something");
    }
    content = List.copyOf(content);
  }

  /** The common case: prose standing in for a run of turns. */
  public static Summary text(TurnId from, TurnId through, String text) {
    return new Summary(from, through, List.of(new Block.Text(text)));
  }

  /** Whether {@code turn} is one of the turns this stands in for. */
  public boolean covers(TurnId turn) {
    return turn.value() >= from.value() && turn.value() <= through.value();
  }
}
