package org.jwcarman.nessy.engine.history;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;

/**
 * One thing that happened to an agent, written down.
 *
 * <p><b>Not a message.</b> These are facts, and nothing here says how any of them should be shown
 * to a model -- that is {@code Turn}'s business, derived at call time and thrown away afterwards.
 * The distinction is what lets a fact be recorded that no model ever sees, and what stops every
 * provider adapter having to interpret one. "Message" is a provider's word, and it belongs in the
 * adapters that speak to providers.
 *
 * <p><b>Nor an event.</b> That word is spent on the narration a UI watches -- an agent beginning to
 * think, work queued behind a turn, a retry scheduled, an approval outstanding. Most of those leave
 * nothing here, and the few that overlap would be indistinguishable if both were called events. An
 * entry is what is durable; an event is what is announced.
 *
 * <p>Every entry carries where it sits: {@code seq} orders the whole story, and {@code turn} is the
 * seq of the observation that opened the turn it belongs to -- so a turn needs no identifier of its
 * own.
 *
 * <p>The {@code "type"} discriminators are a stored format. Changing one is a migration.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = HistoryEntry.ObservationReceived.class, name = "observation-received"),
  @JsonSubTypes.Type(value = HistoryEntry.InferenceAnswered.class, name = "inference-answered"),
  @JsonSubTypes.Type(value = HistoryEntry.InferenceFailed.class, name = "inference-failed"),
  @JsonSubTypes.Type(value = HistoryEntry.InferenceRefused.class, name = "inference-refused"),
  @JsonSubTypes.Type(
      value = HistoryEntry.InferenceRequestedActions.class,
      name = "inference-requested-actions"),
  @JsonSubTypes.Type(value = HistoryEntry.ToolSucceeded.class, name = "tool-succeeded"),
  @JsonSubTypes.Type(value = HistoryEntry.ToolFailed.class, name = "tool-failed"),
  @JsonSubTypes.Type(value = HistoryEntry.ToolApproved.class, name = "tool-approved"),
  @JsonSubTypes.Type(value = HistoryEntry.ToolDenied.class, name = "tool-denied")
})
public sealed interface HistoryEntry {

  Seq seq();

  TurnId turn();

  /**
   * An observation arrived and a turn opened on it.
   *
   * <p>Holds the observation as the model would read it, not as the application handed it over:
   * {@code <O>} ends at the renderer, and this is the first thing on the far side of it.
   */
  record ObservationReceived(Seq seq, TurnId turn, List<Block.ObservationContent> blocks)
      implements HistoryEntry {

    public ObservationReceived {
      Objects.requireNonNull(blocks, "blocks must not be null");
      if (blocks.isEmpty()) {
        // Closing the other half of the same hole as the empty text block: an entry with
        // no blocks renders to nothing just as surely as a block with no text. Anthropic
        // returns a refusal as a 200 carrying no content at all, and an adapter that made
        // an answer of it would store an emptiness to be replayed forever.
        throw new IllegalArgumentException("observation must have at least one block");
      }
      blocks = List.copyOf(blocks);
    }

    /** Opens a turn: the observation's seq is also the turn's. */
    public static ObservationReceived opening(Seq seq, List<Block.ObservationContent> blocks) {
      return new ObservationReceived(seq, seq.opensTurn(), blocks);
    }

    /** From the raw position the fold assigned. */
    public static ObservationReceived opening(long seq, List<Block.ObservationContent> blocks) {
      return opening(new Seq(seq), blocks);
    }

    public static List<Block.ObservationContent> text(String text) {
      return List.of(new Block.Text(text));
    }
  }

  /** The call came back with an answer, and the turn ended. */
  record InferenceAnswered(Seq seq, TurnId turn, List<Block.AnswerContent> blocks)
      implements HistoryEntry {

    public InferenceAnswered {
      Objects.requireNonNull(blocks, "blocks must not be null");
      if (blocks.isEmpty()) {
        throw new IllegalArgumentException("answer must have at least one block");
      }
      blocks = List.copyOf(blocks);
    }

    public static List<Block.AnswerContent> text(String text) {
      return List.of(new Block.Text(text));
    }

    public static InferenceAnswered of(long seq, long turn, String text) {
      return new InferenceAnswered(new Seq(seq), new TurnId(turn), List.of(new Block.Text(text)));
    }
  }

  /**
   * The turn ended without an answer, and may be tried again.
   *
   * <p>"Not this time." Nothing about the conversation is wrong; a call did not complete. What that
   * means for what the model is shown is the projection's business, not this entry's.
   */
  record InferenceFailed(Seq seq, TurnId turn) implements HistoryEntry {}

  /**
   * The call was declined, and would be declined again.
   *
   * <p>"Not ever." The whole story is re-sent on every later turn, so a question that is refused
   * keeps the conversation refused for as long as it is still being sent -- measured: an HTTP 200,
   * the input billed, every time. Ending the turn is not enough on its own.
   *
   * <p>Named for the call rather than for the model, because we do not know it was the model. A
   * content filter at the provider's edge, a safety gateway or a routing layer can decline without
   * anything ever reaching one, and this entry should not claim otherwise.
   *
   * <p><b>This entry states only that.</b> It used to be called {@code ObservationSetAside} and it
   * named a remedy -- an instruction to other readers about how to treat an earlier entry -- which
   * meant four places had to agree on what it meant, and every provider adapter had to invent a
   * sentence for it. What to do about the refused observation is a decision about the projection,
   * taken once, where the projection is built. Nothing is destroyed either way: the observation
   * stays exactly where it is.
   */
  record InferenceRefused(Seq seq, TurnId turn) implements HistoryEntry {}

  /**
   * The call came back asking for things to be done, and the turn stayed open.
   *
   * <p>The fourth outcome of an inference, and the only one that does not end a turn. The others
   * are terminal because there is nothing left owing; this one is the opposite -- it is the moment
   * the engine takes on obligations, and the turn cannot close until every one of them has an entry
   * of its own.
   *
   * <p><b>Requests batch; results do not.</b> One entry holds the whole of what came back, because
   * it arrived as one message and is re-sent as one -- splitting it would lose the order within it,
   * and order is load-bearing when a vendor's reasoning state sits between the calls. Results are
   * written one at a time, as each arrives, because they finish at different moments and a batch
   * would mean holding finished work undurable while waiting on slow work. The projection puts them
   * back together.
   *
   * @param blocks everything the model said, calls and prose and vendor state alike, in order
   */
  record InferenceRequestedActions(Seq seq, TurnId turn, List<Block.ActionRequestContent> blocks)
      implements HistoryEntry {

    public InferenceRequestedActions {
      Objects.requireNonNull(blocks, "blocks must not be null");
      if (blocks.stream().noneMatch(Block.ToolCall.class::isInstance)) {
        // Without a call this entry says a turn is outstanding while owing nothing, and
        // nothing will ever arrive to close it -- the agent waits forever on work it never
        // asked for. An inference that asked for nothing is an answer, and there is an
        // entry for that.
        throw new IllegalArgumentException("a request for actions must contain at least one call");
      }
      blocks = List.copyOf(blocks);
    }

    /** The calls this entry obliges an outcome for, in the order the model made them. */
    public List<Block.ToolCall> calls() {
      return blocks.stream()
          .filter(Block.ToolCall.class::isInstance)
          .map(Block.ToolCall.class::cast)
          .toList();
    }
  }

  /**
   * A tool ran and produced something.
   *
   * <p>One of the three ways a call is discharged, and the {@code callId} is the whole of the
   * correspondence: it names the {@link Block.ToolCall} this answers, and every provider matches
   * them by that id rather than by position.
   */
  record ToolSucceeded(Seq seq, TurnId turn, CallId callId, List<Block.ToolResultContent> blocks)
      implements HistoryEntry {

    public ToolSucceeded {
      callId = requireCallId(callId);
      Objects.requireNonNull(blocks, "blocks must not be null");
      if (blocks.isEmpty()) {
        // A tool that returns nothing still has to say so, because the wire requires a
        // result for every call and an empty one is not a result -- it is a request that
        // renders to nothing and gets re-sent that way forever.
        throw new IllegalArgumentException("a result must have at least one block");
      }
      blocks = List.copyOf(blocks);
    }

    public static List<Block.ToolResultContent> text(String text) {
      return List.of(new Block.Text(text));
    }
  }

  /**
   * A tool was run and did not produce something.
   *
   * <p>Discharges the call just as firmly as success does. The obligation is to answer, not to
   * answer well, and a model handles being told a lookup failed perfectly happily -- it is being
   * told nothing that wedges the conversation.
   *
   * <p>The message is content, not diagnostics: it is what the model reads, so it should say what
   * went wrong in terms the model can act on. What went wrong for an operator belongs in a log,
   * where a stack trace is welcome and a re-sent transcript is not.
   */
  record ToolFailed(Seq seq, TurnId turn, CallId callId, String message) implements HistoryEntry {

    public ToolFailed {
      callId = requireCallId(callId);
      message = requireMessage(message, "message");
    }
  }

  /**
   * A call was allowed to run.
   *
   * <p><b>The one entry no model ever sees.</b> Everything else here is a fact about the
   * conversation; this is a fact about the engine, and it is written down for exactly one reason:
   * so that "every call that ran was approved" stops being something the code promises and becomes
   * something the story proves. A {@code tool-succeeded} whose call has no {@code tool-approved}
   * before it in the same turn is a violation anybody can find by reading the rows.
   *
   * <p>Here rather than on a separate audit channel because this is written inside the fold's
   * transaction, under the agent's row lock, in seq order. A second store would be a second write
   * that can fail on its own -- leaving a tool that ran with no approval recorded, or an approval
   * recorded for a call that never ran -- and two records that can disagree are worse evidence than
   * one that cannot.
   *
   * <p>{@code reference} is the join to whoever actually decided, and nothing here interprets it.
   * The evidence itself -- identities, votes, policy input -- belongs to the subsystem that
   * gathered it, which will always be a better record of that than this table could be.
   */
  record ToolApproved(Seq seq, TurnId turn, CallId callId, Optional<String> reference)
      implements HistoryEntry {

    public ToolApproved {
      callId = requireCallId(callId);
      Objects.requireNonNull(reference, "reference must not be null");
    }
  }

  /**
   * A call was not run at all, because an approver said no.
   *
   * <p>Separate from {@link ToolFailed} because it is a different fact and the difference is worth
   * keeping: nothing was attempted, so nothing happened in the world, and re-running it later is
   * meaningful in a way that re-running a failure is not. On the wire the two look alike -- both
   * become a result on the call -- but that is the adapter's flattening, not ours to do in advance.
   */
  record ToolDenied(Seq seq, TurnId turn, CallId callId, String reason, Optional<String> reference)
      implements HistoryEntry {

    public ToolDenied {
      callId = requireCallId(callId);
      reason = requireMessage(reason, "reason");
      Objects.requireNonNull(reference, "reference must not be null");
    }

    public ToolDenied(Seq seq, TurnId turn, CallId callId, String reason) {
      this(seq, turn, callId, reason, Optional.empty());
    }
  }

  private static CallId requireCallId(CallId callId) {
    // The id is the only link back to the call, and CallId already refuses a blank one -- so
    // all that is left here is that an entry names a call at all.
    return Objects.requireNonNull(callId, "callId must not be null");
  }

  private static String requireMessage(String message, String name) {
    Objects.requireNonNull(message, name + " must not be null");
    if (message.isBlank()) {
      // Closes the same hole as the empty text block: this is the entire content of the
      // result the model reads, and a blank one tells it a call finished without telling it
      // anything about how.
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return message;
  }
}
