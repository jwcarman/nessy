package org.jwcarman.nessy.engine.token;

import java.util.List;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.engine.history.HistoryEntry;

/**
 * The crude one: characters over three, plus a fixed charge for existing at all.
 *
 * <p>Deliberately crude and meant to be generous. Every provider tokenizes differently and none of
 * them tokenize characters, so any number here is wrong; the useful question is which direction it
 * is wrong in. Overestimating wastes context, underestimating stalls a conversation, so this rounds
 * up and adds a per-entry overhead for the role and framing a wire will add.
 *
 * <p><b>It does not always manage generous.</b> Measured against {@code o200k_base}: English prose
 * comes out 1.8-2.1x over, JSON 1.3x over -- and CJK 0.69x, URL- and UUID-heavy text 0.95x. Both of
 * those are under, which is the direction that stalls a conversation, and both are ordinary traffic
 * for an agent. One ratio cannot cover a range that runs from about 1.4 characters per token to
 * about 50.
 *
 * <p>So the honest fix is a real tokenizer, not a better constant. It belongs behind this same
 * interface, and nothing about the storage or the queries changes when it arrives.
 *
 * <p>Nothing reads the estimate today -- how much story is sent is counted in whole turns -- so
 * this is written and kept rather than relied upon.
 */
public class CharacterCountEstimator implements TokenEstimator {

  private static final int CHARACTERS_PER_TOKEN = 3;

  private static final int PER_MESSAGE_OVERHEAD = 8;

  @Override
  public int estimate(HistoryEntry message) {
    // The overhead pays for role and framing a wire will add, so an entry that never reaches
    // a wire is charged neither it nor anything else. Free is the true answer here, not a
    // lenient one: charging a grant would make a conversation look more expensive than it can
    // ever be, and a budget built on that would trim turns to make room for nothing.
    return sent(message)
        ? PER_MESSAGE_OVERHEAD + blocks(message).stream().mapToInt(this::estimate).sum()
        : 0;
  }

  /**
   * Whether this entry is ever put in front of a model.
   *
   * <p>Almost all of them are, and the one that is not is the point of the method: a grant is a
   * fact about the engine written down for audit, passed over by the projection, and so costs
   * nothing. Exhaustive rather than a negated instanceof, so a later entry that is also never sent
   * has to say so here instead of being silently charged.
   */
  private static boolean sent(HistoryEntry message) {
    return switch (message) {
      case HistoryEntry.ToolApproved _ -> false;
      case HistoryEntry.ObservationReceived _,
          HistoryEntry.InferenceAnswered _,
          HistoryEntry.InferenceFailed _,
          HistoryEntry.InferenceRefused _,
          HistoryEntry.InferenceRequestedActions _,
          HistoryEntry.ToolSucceeded _,
          HistoryEntry.ToolFailed _,
          HistoryEntry.ToolDenied _ ->
          true;
    };
  }

  /**
   * Exhaustive over the sealed block hierarchy on purpose, with no fallback. A block type added to
   * {@code Block} fails to compile here, which is where the decision about what it costs belongs --
   * the previous default charged an invented figure for anything unrecognised, so a new block type
   * could quietly make a conversation look cheaper than it is and nothing would say so.
   */
  @Override
  public int estimate(Block block) {
    return switch (block) {
      case Block.Text(String text) -> Math.ceilDiv(text.length(), CHARACTERS_PER_TOKEN);
      // Charged exactly as text. What it costs is what it is -- characters on the wire --
      // and a transcript that shows it quietly still pays for it in full.
      case Block.Commentary(String text) -> Math.ceilDiv(text.length(), CHARACTERS_PER_TOKEN);
      // Charged, not free: a vendor's reasoning state is real content on the wire and is
      // re-sent with every later turn. Estimating it at zero would quietly overspend a
      // budget by exactly the amount nobody can see.
      case Block.Provider(String _, String payload) ->
          Math.ceilDiv(payload.length(), CHARACTERS_PER_TOKEN);
      // All three fields are billed, not just the arguments. The id is quoted back by the
      // result, so it is on the wire twice, and the name is on it once per call rather than
      // once per tool. Charging only the arguments would undercount every zero-argument
      // call to nothing, and those are exactly the calls a model makes in bulk.
      case Block.ToolCall(CallId id, ToolName name, String arguments) ->
          Math.ceilDiv(
              id.value().length() + name.value().length() + arguments.length(),
              CHARACTERS_PER_TOKEN);
    };
  }

  /** The content a message carries, if any. */
  private static List<? extends Block> blocks(HistoryEntry message) {
    return switch (message) {
      case HistoryEntry.ObservationReceived observation -> observation.blocks();
      case HistoryEntry.InferenceAnswered answer -> answer.blocks();
      // Neither carries content: they say a turn ended, and the wire renders a short
      // sentence for them. The per-message overhead alone is the honest charge.
      case HistoryEntry.InferenceFailed _ -> List.of();
      case HistoryEntry.InferenceRefused _ -> List.of();
      case HistoryEntry.InferenceRequestedActions request -> request.blocks();
      case HistoryEntry.ToolSucceeded result -> result.blocks();
      // The message is the whole content of the result the model reads, so it is charged
      // exactly as the text block the adapter will turn it into. The call id rides along on
      // the wire too and is charged where it was first written, on the call itself.
      case HistoryEntry.ToolFailed failure -> List.of(new Block.Text(failure.message()));
      case HistoryEntry.ToolDenied denial -> List.of(new Block.Text(denial.reason()));
      // Never reaches a wire; `sent` has already answered zero for it.
      case HistoryEntry.ToolApproved _ -> List.of();
    };
  }
}
