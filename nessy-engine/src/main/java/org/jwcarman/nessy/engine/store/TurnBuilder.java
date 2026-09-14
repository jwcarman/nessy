package org.jwcarman.nessy.engine.store;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;

/**
 * One turn, under construction, as the story is reduced forward.
 *
 * <p>Entries arrive in seq order: an observation opens a turn, rounds of calls and their outcomes
 * accumulate in the middle, and a terminal entry closes it. Which entries do which is decided by an
 * exhaustive switch in {@link Turns}, so a new kind of entry cannot be added without somebody
 * saying what it does to a turn.
 *
 * <p>Reduced forward rather than grouped. Grouping by turn and calling the last entry the result
 * works only while a turn holds at most one entry after its observation; the moment a turn can
 * contain an exchange, the last entry of an unfinished one is a tool result, and grouping would
 * report the turn as closed by something that closes nothing.
 *
 * <p>Mutable and short-lived: one pass over one agent's rows, then discarded.
 */
final class TurnBuilder {

  private final Observation observation;
  private final List<Exchange> exchanges = new ArrayList<>();
  private TurnResult result;
  private int tokens;

  /**
   * The round still being answered, or null between rounds.
   *
   * <p>One field rather than three, because a request, its outcomes and the seq they belong to are
   * only ever meaningful together -- separate fields would let two of them be set and the third
   * not, which is a state this builder has no meaning for.
   */
  private OpenExchange open;

  TurnBuilder(Observation observation, int tokens) {
    this.observation = observation;
    this.tokens = tokens;
  }

  /**
   * The model asked for a round of work.
   *
   * <p>Any round already open is closed first. Rounds are strictly sequential -- the model is not
   * asked again until every call in the previous round has an outcome -- so a new request arriving
   * is itself the proof that the one before it finished.
   */
  void requested(List<Block.ActionRequestContent> request, Seq seq, int tokens) {
    requireNotClosed(seq);
    flush();
    this.open = new OpenExchange(seq, request);
    this.tokens += tokens;
  }

  /** One call was discharged. Order of arrival is kept; it need not be the order asked. */
  void resolved(ToolOutcome outcome, Seq seq, int tokens) {
    requireNotClosed(seq);
    if (open == null) {
      // The outcome names a call, and in seq order the request that made it comes first.
      // Reaching here means the story has a result for something nobody asked for.
      throw new IllegalStateException(
          "entry "
              + seq
              + " resolves call "
              + outcome.callId()
              + ", which this turn never requested");
    }
    open.outcomes().add(outcome);
    this.tokens += tokens;
  }

  void closedBy(TurnResult result, Seq seq, int tokens) {
    requireNotClosed(seq);
    flush();
    this.result = result;
    this.tokens += tokens;
  }

  /** The turn so far. An unclosed one is a real thing: it is the turn in flight. */
  Turn build() {
    flush();
    return new Turn(observation.seq().opensTurn(), observation, exchanges, result, tokens);
  }

  private void flush() {
    if (open != null) {
      exchanges.add(new Exchange(open.seq(), open.request(), open.outcomes()));
      open = null;
    }
  }

  private void requireNotClosed(Seq seq) {
    if (result != null) {
      // Append-only and seq-ordered, so an entry landing after a turn's terminal one means
      // the numbering or the fold is wrong, not something to paper over.
      throw new IllegalStateException(
          "entry " + seq + " belongs to turn " + observation.seq() + ", which was already closed");
    }
  }

  /** A round that has been asked for and not yet gathered up. */
  private record OpenExchange(
      Seq seq, List<Block.ActionRequestContent> request, List<ToolOutcome> outcomes) {

    OpenExchange(Seq seq, List<Block.ActionRequestContent> request) {
      this(seq, request, new ArrayList<>());
    }
  }
}
