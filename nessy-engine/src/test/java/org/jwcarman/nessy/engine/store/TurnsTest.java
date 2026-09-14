package org.jwcarman.nessy.engine.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.history.HistoryEntry;

/**
 * The projection, tested where it is cheapest to test: a list of rows in, conversations out.
 *
 * <p>No database. What these assert is the reduction itself -- which entries open a turn, which
 * close one, and which do neither -- and that is the part a wrong answer corrupts silently. A turn
 * assembled wrongly still looks like a conversation; it is just one the model never had.
 */
class TurnsTest {

  private final List<Stored> rows = new ArrayList<>();
  private long seq;

  private long observed(String text) {
    long at = ++seq;
    rows.add(
        new Stored(
            HistoryEntry.ObservationReceived.opening(
                new Seq(at), HistoryEntry.ObservationReceived.text(text)),
            1));
    return at;
  }

  private void requested(long turn, Block.ActionRequestContent... blocks) {
    rows.add(
        new Stored(
            new HistoryEntry.InferenceRequestedActions(
                new Seq(++seq), new TurnId(turn), List.of(blocks)),
            1));
  }

  private void succeeded(long turn, String callId, String text) {
    rows.add(
        new Stored(
            new HistoryEntry.ToolSucceeded(
                new Seq(++seq),
                new TurnId(turn),
                new CallId(callId),
                HistoryEntry.ToolSucceeded.text(text)),
            1));
  }

  private void answered(long turn, String text) {
    rows.add(new Stored(HistoryEntry.InferenceAnswered.of(++seq, turn, text), 1));
  }

  private List<Turn> assemble() {
    return Turns.assemble(rows);
  }

  private void approved(long turn, String callId) {
    rows.add(
        new Stored(
            new HistoryEntry.ToolApproved(
                new Seq(++seq), new TurnId(turn), new CallId(callId), java.util.Optional.empty()),
            0));
  }

  private static Block.ToolCall call(String id, String name) {
    return new Block.ToolCall(id, name, "{}");
  }

  /** The shape that was true before tools and has to stay true: nothing gained an exchange. */
  @Test
  void aTurnWithNoCallsHasNoExchanges() {
    long turn = observed("hello");
    answered(turn, "hi");

    assertThat(assemble())
        .singleElement()
        .satisfies(
            one -> {
              assertThat(one.exchanges()).isEmpty();
              assertThat(one.complete()).isTrue();
            });
  }

  /**
   * The whole point of the entry vocabulary: asking for work does not end a turn. If it did, the
   * next observation would open a second turn and the answer would land in the wrong one.
   */
  @Test
  void askingForWorkLeavesTheTurnOpen() {
    long turn = observed("what lake?");
    requested(turn, call("c1", "lookup"));

    assertThat(assemble())
        .singleElement()
        .satisfies(
            one -> {
              assertThat(one.complete()).as("still in flight").isFalse();
              assertThat(one.exchanges())
                  .singleElement()
                  .satisfies(round -> assertThat(round.complete()).isFalse());
            });
  }

  /** Requests batch and results arrive one at a time; the projection puts them back together. */
  @Test
  void resultsAreGatheredAgainstTheRequestTheyAnswer() {
    long turn = observed("two things");
    requested(turn, new Block.Commentary("let me look"), call("c1", "lookup"), call("c2", "other"));
    succeeded(turn, "c2", "second");
    succeeded(turn, "c1", "first");
    answered(turn, "done");

    Exchange round = assemble().getFirst().exchanges().getFirst();
    assertThat(round.calls())
        .extracting(Block.ToolCall::id)
        .containsExactly(new CallId("c1"), new CallId("c2"));
    assertThat(round.outcomes())
        .extracting(ToolOutcome::callId)
        .as("kept in the order they came back, which need not be the order asked")
        .containsExactly(new CallId("c2"), new CallId("c1"));
    assertThat(round.complete()).isTrue();
    assertThat(round.request())
        .as("the prose the model wrote alongside is kept, in order")
        .first()
        .isEqualTo(new Block.Commentary("let me look"));
  }

  /** Rounds are sequential, and a second request is itself the proof the first one finished. */
  @Test
  void aTurnCanTakeSeveralRounds() {
    long turn = observed("dig");
    requested(turn, call("c1", "lookup"));
    succeeded(turn, "c1", "one");
    requested(turn, call("c2", "lookup"));
    succeeded(turn, "c2", "two");
    answered(turn, "found it");

    Turn one = assemble().getFirst();
    assertThat(one.exchanges()).hasSize(2);
    assertThat(one.exchanges()).allSatisfy(round -> assertThat(round.complete()).isTrue());
    assertThat(one.result()).isInstanceOf(TurnResult.Answered.class);
  }

  /** Every entry in the turn is charged, not just the ones that opened and closed it. */
  @Test
  void everyEntryInATurnIsCharged() {
    long turn = observed("count me");
    requested(turn, call("c1", "lookup"));
    succeeded(turn, "c1", "one");
    answered(turn, "done");

    assertThat(assemble().getFirst().tokens()).isEqualTo(4);
  }

  /** Turns still separate on their observations, with exchanges landing in the right one. */
  @Test
  void exchangesBelongToTheTurnTheyHappenedIn() {
    long first = observed("first");
    requested(first, call("c1", "lookup"));
    succeeded(first, "c1", "one");
    answered(first, "answer one");
    long second = observed("second");
    answered(second, "answer two");

    List<Turn> turns = assemble();
    assertThat(turns).hasSize(2);
    assertThat(turns.getFirst().exchanges()).hasSize(1);
    assertThat(turns.getLast().exchanges()).isEmpty();
  }

  /**
   * A result for a call nobody made means the story is inconsistent, and no projection can invent
   * the request it answers. Loud beats a turn that quietly omits it.
   */
  @Test
  void aResultWithoutARequestIsRefused() {
    long turn = observed("hello");
    succeeded(turn, "c1", "out of nowhere");

    assertThatThrownBy(this::assemble)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("never requested");
  }

  /** Append-only and seq-ordered: nothing lands in a turn after the entry that ended it. */
  @Test
  void anEntryAfterTheEndingIsRefused() {
    long turn = observed("hello");
    answered(turn, "hi");
    requested(turn, call("c1", "lookup"));

    assertThatThrownBy(this::assemble)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already closed");
  }

  /**
   * The one entry the projection passes over.
   *
   * <p>A grant is a fact about the engine, not about the conversation. It discharges nothing and
   * closes nothing, and if it reached a turn an adapter would have to invent something to do with
   * it -- which is exactly the thing the entry is written down to avoid needing.
   */
  @Test
  void aGrantChangesNothingAboutTheTurnItSitsIn() {
    long turn = observed("what lake?");
    requested(turn, call("c1", "lookup"));
    approved(turn, "c1");
    succeeded(turn, "c1", "deep");
    answered(turn, "very deep");

    Turn one = assemble().getFirst();
    Exchange round = one.exchanges().getFirst();
    assertThat(round.outcomes())
        .as("the grant is not an outcome: it discharges nothing")
        .hasSize(1);
    assertThat(round.complete()).isTrue();
    assertThat(one.result()).isInstanceOf(TurnResult.Answered.class);
  }

  /** And it does not open, close or split one either -- it is simply not there. */
  @Test
  void aTurnReadsIdenticallyWhetherOrNotItsGrantsAreInTheStory() {
    long turn = observed("what lake?");
    requested(turn, call("c1", "lookup"));
    approved(turn, "c1");
    succeeded(turn, "c1", "deep");
    answered(turn, "very deep");
    List<Turn> withGrant = assemble();

    rows.removeIf(row -> row.message() instanceof HistoryEntry.ToolApproved);
    assertThat(assemble())
        .usingRecursiveComparison()
        .ignoringFields("id", "observation.seq", "tokens", "exchanges.seq")
        .isEqualTo(withGrant);
  }
}
