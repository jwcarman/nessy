package org.jwcarman.nessy.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where something sits in an agent's story.
 *
 * <p>One number line per agent, assigned by the fold and never reused: {@code (agent_type,
 * agent_id, seq)} is the history table's primary key, so a seq that disagreed with what is stored
 * fails the insert rather than quietly writing a second entry at an occupied position.
 *
 * <p><b>A type because of one specific mistake.</b> Every entry is {@code (seq, turn, ...)} and
 * both were {@code long}, so {@code new ToolSucceeded(turn, seq, ...)} compiled and ran and
 * produced an entry filed under the wrong turn -- a corruption no test catches unless it happens to
 * assert both numbers. The two are the same number line and different meanings, which is exactly
 * when a compiler needs telling.
 */
public record Seq(@JsonValue long value) implements Comparable<Seq> {

  /** Before anything has been written down. An agent's first entry is {@code 1}. */
  public static final Seq NONE = new Seq(0);

  public Seq {
    if (value < 0) {
      throw new IllegalArgumentException("seq must not be negative: " + value);
    }
  }

  @JsonCreator
  public static Seq of(long value) {
    return new Seq(value);
  }

  /** The next position. The fold numbers what it writes by counting on from the last. */
  public Seq next() {
    return new Seq(value + 1);
  }

  /**
   * The turn this seq opens.
   *
   * <p>An observation's seq is also its turn's id, which is why a turn needs no identifier of its
   * own. Named rather than implicit so the one place that crosses between the two number lines says
   * it out loud.
   */
  public TurnId opensTurn() {
    return new TurnId(value);
  }

  @Override
  public int compareTo(Seq other) {
    return Long.compare(value, other.value);
  }

  @Override
  public String toString() {
    return Long.toString(value);
  }
}
