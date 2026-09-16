package org.jwcarman.nessy.engine.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.engine.history.HistoryEntry;

/**
 * The count is written into the row when the message is written, and every budget downstream reads
 * it rather than recomputing — so what it charges is worth pinning.
 */
class CharacterCountEstimatorTest {

  private final CharacterCountEstimator estimator = new CharacterCountEstimator();

  private static HistoryEntry.ObservationReceived observation(String... texts) {
    List<Block.ObservationContent> blocks =
        List.of(texts).stream().<Block.ObservationContent>map(Block.Text::new).toList();
    return new HistoryEntry.ObservationReceived(new Seq(1), new TurnId(1), blocks);
  }

  @Test
  void aBlockIsChargedItsTextRoundedUp() {
    // Ten characters at three per token.
    assertThat(estimator.estimate(new Block.Text("abcdefghij"))).isEqualTo(4);
  }

  /**
   * A zero-argument call still costs something: the name and the id are on the wire whether or not
   * there are arguments, and the id is on it twice once the result quotes it back.
   */
  @Test
  void aCallIsChargedItsIdAndNameAsWellAsItsArguments() {
    // "call_1" + "joke" + "{}" is twelve characters at three per token.
    assertThat(estimator.estimate(new Block.ToolCall("call_1", "joke", "{}"))).isEqualTo(4);
  }

  @Test
  void aMessageIsItsBlocksPlusTheOverhead() {
    assertThat(estimator.estimate(observation("abcdefghij"))).isEqualTo(4 + 8);
  }

  @Test
  void everyBlockInAMessageIsCounted() {
    // Three blocks of two characters: one token each, since each is rounded up on its own.
    assertThat(estimator.estimate(observation("ab", "cd", "ef"))).isEqualTo(3 + 8);
  }

  /**
   * A message carrying no content is not free. It says a turn ended, the wire renders a short
   * sentence for it, and the per-message overhead is what stands in for that.
   */
  @Test
  void aMessageWithNoContentStillCostsItsOverhead() {
    assertThat(estimator.estimate(new HistoryEntry.InferenceFailed(new Seq(1), new TurnId(1))))
        .isEqualTo(8);
    assertThat(estimator.estimate(new HistoryEntry.InferenceRefused(new Seq(1), new TurnId(1))))
        .isEqualTo(8);
  }

  /**
   * Rounding per block rather than over the total makes the estimate sensitive to how content was
   * split — five two-character blocks cost more than the same ten characters in one. That is the
   * price of the block being the unit of estimation, which is what lets a real tokenizer replace
   * this by tokenizing each block it is handed.
   */
  @Test
  void splittingContentAcrossBlocksCostsMoreThanTheSameTextInOne() {
    assertThat(estimator.estimate(observation("ab", "cd", "ef", "gh", "ij")))
        .isGreaterThan(estimator.estimate(observation("abcdefghij")));
  }

  /**
   * Free, and not as a kindness. A grant never reaches a wire, so charging it -- even the per-entry
   * overhead -- would make a conversation look more expensive than it can ever be, and a budget
   * built on that would trim real turns to make room for nothing.
   */
  @Test
  void aGrantCostsNothingAtAll() {
    assertThat(
            estimator.estimate(
                new HistoryEntry.ToolApproved(
                    new Seq(3),
                    new TurnId(1),
                    new CallId("c1"),
                    java.util.Optional.of("change-1187"))))
        .isZero();
  }

  @Test
  void providerStateIsChargedItsPayloadAndAnApprovalCostsNothing() {
    assertThat(estimator.estimate(new Block.Provider("v", "abcdef"))).isEqualTo(2);
    assertThat(
            estimator.estimate(
                new HistoryEntry.ToolApproved(
                    new Seq(4), new TurnId(1), new CallId("c1"), java.util.Optional.empty())))
        .isZero();
  }
}
