package org.jwcarman.nessy.engine.store;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.history.HistoryEntry;

/**
 * Reduces a story into turns.
 *
 * <p>The projection, and the whole of it. Entries are read forward in seq order and folded into
 * conversations; what comes out mentions no entries, no sequence numbers beyond a turn's own, and
 * no storage at all.
 *
 * <p>Provider-neutral by construction, because it takes no view on what should be sent. Every
 * adapter is handed the same turns and decides for itself how its provider is best asked.
 */
final class Turns {

  private Turns() {}

  static List<Turn> assemble(List<Stored> stored) {
    List<Turn> turns = new ArrayList<>();
    TurnBuilder current = null;

    for (Stored row : stored) {
      HistoryEntry entry = row.message();
      switch (entry) {

        // Opens one. A turn still open before it was left that way by a fold that ended
        // one turn and began the next, so it is finished and kept as it stands.
        case HistoryEntry.ObservationReceived(Seq seq, TurnId _, var blocks) -> {
          if (current != null) {
            turns.add(current.build());
          }
          current = new TurnBuilder(new Observation(seq, blocks), row.tokens());
        }

        // Close one. There is still no fourth way for a turn to end, and this switch is
        // what makes that true: a new kind of entry stops it compiling until somebody says
        // whether it ends a turn and what that ending is.
        case HistoryEntry.InferenceAnswered(Seq seq, TurnId _, var blocks) ->
            require(current, entry).closedBy(new TurnResult.Answered(blocks), seq, row.tokens());
        case HistoryEntry.InferenceFailed(Seq seq, TurnId _) ->
            require(current, entry).closedBy(new TurnResult.Failed(), seq, row.tokens());
        case HistoryEntry.InferenceRefused(Seq seq, TurnId _) ->
            require(current, entry).closedBy(new TurnResult.Refused(), seq, row.tokens());

        // Opens a round inside the turn without closing the turn. The only entry from an
        // inference that is not terminal, because it is the one that takes on obligations
        // rather than settling them.
        case HistoryEntry.InferenceRequestedActions(Seq seq, TurnId _, var blocks) ->
            require(current, entry).requested(blocks, seq, row.tokens());

        // Discharge one call each. Written separately as they arrive; gathered back
        // against the request they answer here, once, rather than by every adapter.
        case HistoryEntry.ToolSucceeded(Seq seq, TurnId _, CallId callId, var blocks) ->
            require(current, entry)
                .resolved(new ToolOutcome.Succeeded(callId, blocks), seq, row.tokens());
        case HistoryEntry.ToolFailed(Seq seq, TurnId _, CallId callId, String message) ->
            require(current, entry)
                .resolved(new ToolOutcome.Failed(callId, message), seq, row.tokens());
        case HistoryEntry.ToolDenied(Seq seq, TurnId _, CallId callId, String reason, var _) ->
            require(current, entry)
                .resolved(new ToolOutcome.Denied(callId, reason), seq, row.tokens());

        // Recorded, and deliberately invisible. A grant is a fact about the engine, not
        // about the conversation: it discharges nothing, closes nothing, and adds nothing
        // a model could read. Every other entry here shapes a turn; this one is passed
        // over, which is exactly what makes it safe to write down.
        case HistoryEntry.ToolApproved _ -> {
          // Nothing. The turn is unchanged by permission having been granted.
        }
      }
    }

    if (current != null) {
      turns.add(current.build());
    }
    return List.copyOf(turns);
  }

  private static TurnBuilder require(TurnBuilder current, HistoryEntry entry) {
    if (current == null) {
      // Every turn is opened by its observation and seq order puts that first. Reaching here
      // means the story is missing one, which no projection can invent.
      throw new IllegalStateException(
          "entry "
              + entry.seq()
              + " belongs to turn "
              + entry.turn()
              + ", which has no observation, so it never opened");
    }
    return current;
  }
}
