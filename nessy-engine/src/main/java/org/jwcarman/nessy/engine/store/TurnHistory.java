package org.jwcarman.nessy.engine.store;

import java.util.List;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Turn;

/**
 * The story of one agent, offered as turns.
 *
 * <p>What an assembler reads, and all of it. Reads only: building a request is not an occasion to
 * write anything, and an assembler that could append could rewrite what it was asked to summarise.
 * The same object implements this and appends -- the split is about what each collaborator can
 * reach, not about where the code lives.
 *
 * <p>Turns rather than messages because a turn is the only unit a model can be handed. Trim a
 * message list at an arbitrary point and the context can begin with a reply to a question that is
 * no longer present, or end with a question whose answer fell off the end. A boundary between turns
 * cannot do either.
 *
 * <p>Already narrowed to one agent. An assembler is handed the identity and resolves this once, up
 * front, so the reads themselves take none -- which is what stops a long assembly from accidentally
 * mixing two agents' stories halfway through.
 */
public interface TurnHistory {

  /**
   * The most recent turns that fit in a token budget, whole. Two queries however many come back.
   *
   * <p>The boundary lands between turns, never inside one, and a single turn larger than the whole
   * budget is still returned -- a conversation cannot proceed with no context, and an over-large
   * request fails recoverably where an empty one just produces nonsense.
   */
  /** The last {@code turns} turns, whole, most recent last. */
  List<Turn> lastTurns(int turns);

  /**
   * Every turn from this one onward, whole.
   *
   * <p>The shape an assembler wants: one boundary, everything behind it represented some other way,
   * everything ahead of it verbatim.
   */
  List<Turn> turnsFrom(long fromTurn);

  /**
   * The newest {@code turns} turns strictly after {@code through}, whole, oldest first: the tail
   * once a summary covers everything before it.
   *
   * <p>Capped in the query, never in memory. Reading everything after the boundary and keeping the
   * end of it would be right in every test and wrong in production, where the point of a cap is
   * what it stops being read. There is always a cap; "everything" is a large number, not a missing
   * one.
   */
  List<Turn> lastTurnsAfter(TurnId through, int turns);

  /**
   * How many turns follow turn {@code through}, counted in the database rather than loaded. Zero
   * counts them all, as {@link #turnsFrom(long)} reads them all from one.
   */
  long turnsAfter(long through);
}
