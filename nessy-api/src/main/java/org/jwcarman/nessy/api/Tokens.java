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
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One count of tokens, or the absence of one.
 *
 * <p><b>A vendor that said nothing and a vendor that said zero are different facts</b>, and this
 * type is the difference. Zero cache reads means your caching is not working; no cache reads
 * reported means you cannot tell. Anything that collapses the two -- a nullable {@code Integer}
 * read without care, a total that starts at zero -- turns the second into the first, and a metric
 * showing a measured zero is worse than a metric showing nothing, because it looks like a finding.
 *
 * <p><b>Sealed rather than nullable</b> so that the distinction cannot be dropped by accident. A
 * reader has to say what it means to have no count, because the compiler makes it.
 *
 * <p><b>It totals without lying.</b> {@link #plus} leaves {@link Uncounted} alone on both sides, so
 * summing a turn whose vendor never reported cache reads gives back no count rather than a zero,
 * while a turn where one call reported and another did not gives back what was reported. A total is
 * therefore always "at least this much, where anything said anything at all".
 */
public sealed interface Tokens {

  /** Nobody said. Not zero -- zero is somebody saying so. */
  record Uncounted() implements Tokens {}

  /** Somebody said, and this is what they said. */
  record Counted(int count) implements Tokens {
    public Counted {
      if (count < 0) {
        throw new IllegalArgumentException("a count must not be negative: " + count);
      }
    }
  }

  /** No count. */
  static Tokens none() {
    return new Uncounted();
  }

  /** A count. */
  static Tokens of(int count) {
    return new Counted(count);
  }

  /**
   * What a vendor said, where it may not have said anything.
   *
   * <p>Adapters receive nullable boxes from SDKs, and this is the one place that turns an absent
   * one into {@link Uncounted} rather than each adapter deciding for itself.
   *
   * <p>Named rather than overloading {@link #of(int)}: the two would differ only by boxing, so
   * {@code of(null)} would not compile without a cast and {@code of(someInteger)} would silently
   * pick whichever the compiler preferred.
   */
  @JsonCreator
  static Tokens reported(@Nullable Integer count) {
    return count == null ? none() : of(count.intValue());
  }

  /**
   * These and those, added.
   *
   * <p>An absent count contributes nothing and takes nothing away: absent plus absent is still
   * absent, and absent plus a number is that number. That is what lets one quiet call sit in a
   * turn's total without making the whole total unknowable, and what keeps a field nothing ever
   * reported from reading as zero.
   */
  default Tokens plus(Tokens other) {
    Objects.requireNonNull(other, "other must not be null");
    return switch (this) {
      case Uncounted _ -> other;
      case Counted(int mine) ->
          switch (other) {
            case Uncounted _ -> this;
            case Counted(int theirs) -> of(mine + theirs);
          };
    };
  }

  /**
   * These less those, never below nothing.
   *
   * <p>For reading a part out of a whole -- what the spending bought, given what it wasted. Clamped
   * rather than allowed to go negative, because two totals can only disagree by a count one of them
   * absorbed and the other did not, and a negative number of tokens is a worse answer than none
   * left.
   */
  default Tokens minus(Tokens other) {
    Objects.requireNonNull(other, "other must not be null");
    return switch (this) {
      case Uncounted _ -> this;
      case Counted(int mine) ->
          switch (other) {
            case Uncounted _ -> this;
            case Counted(int theirs) -> of(Math.max(0, mine - theirs));
          };
    };
  }

  /**
   * A number where there is one, and nothing where there is not.
   *
   * <p>How this is written down, and deliberately the same shape a nullable count had before this
   * type existed: a stored event whose usage was written by an older build still reads, and a
   * reader looking at the raw JSON sees a number rather than a tagged object. The distinction this
   * type protects is a distinction in Java; on the wire, absent has always been absent.
   */
  @JsonValue
  default @Nullable Integer asReported() {
    return switch (this) {
      case Uncounted _ -> null;
      case Counted(int count) -> count;
    };
  }

  /** Whether anybody said. */
  default boolean counted() {
    return this instanceof Counted;
  }

  /**
   * The count, treating an absent one as nothing.
   *
   * <p>For arithmetic that has to produce a number. A reader deciding whether to record, display or
   * bill should match on the type instead -- that is the whole reason it exists.
   */
  default int orZero() {
    return switch (this) {
      case Uncounted _ -> 0;
      case Counted(int count) -> count;
    };
  }
}
