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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Which turn something belongs to.
 *
 * <p>The {@link Seq} of the observation that opened it, which is why a turn needs no identifier of
 * its own: the first entry of a turn names it, and every entry after it points back.
 *
 * <p><b>Its own type despite sharing a number line with {@code Seq}</b>, and that is the point
 * rather than an awkwardness. The two mean different things -- one is a position, the other is a
 * grouping -- and they sit side by side in every history entry, where a {@code long} could not tell
 * them apart. Crossing between them is deliberate and has exactly one name: {@link
 * Seq#opensTurn()}.
 */
public record TurnId(@JsonValue long value) implements Comparable<TurnId> {

  public TurnId {
    if (value <= 0) {
      // A turn is named by the seq of its opening observation, and seqs start at one. Zero
      // would mean a turn opened by an entry that was never written.
      throw new IllegalArgumentException("turn must be positive: " + value);
    }
  }

  @JsonCreator
  public static TurnId of(long value) {
    return new TurnId(value);
  }

  /** Where this turn began. The observation that opened it sits at exactly this position. */
  public Seq openedAt() {
    return new Seq(value);
  }

  @Override
  public int compareTo(TurnId other) {
    return Long.compare(value, other.value);
  }

  @Override
  public String toString() {
    return Long.toString(value);
  }
}
