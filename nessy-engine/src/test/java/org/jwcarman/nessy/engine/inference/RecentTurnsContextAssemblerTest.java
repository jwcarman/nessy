package org.jwcarman.nessy.engine.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;

/**
 * What an assembler ASKS FOR matters as much as what it returns.
 *
 * <p>An assembler that read the whole story and threw most of it away would pass any test written
 * only against its output, while defeating the point of a budget spent inside the query. So the
 * stub here records the requests, not just the answers.
 */
class RecentTurnsContextAssemblerTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());

  private static Turn turn(long id, String question, String answer) {
    return new Turn(
        new TurnId(id),
        new Observation(new Seq(id), HistoryEntry.ObservationReceived.text(question)),
        List.of(),
        new TurnResult.Answered(HistoryEntry.InferenceAnswered.text(answer)),
        10);
  }

  private static InferenceInvocation invocation() {
    return new InferenceInvocation(TYPE, AGENT, InferenceOptions.of("a-model"));
  }

  /** Records which agent was narrowed to, and every budget the assembler spent. */
  static final class RecordingHistories implements TurnHistories, TurnHistory {

    private final List<Turn> turns;
    private final List<String> narrowedTo = new ArrayList<>();
    private final List<Integer> windows = new ArrayList<>();
    private final List<Long> rangeReads = new ArrayList<>();

    RecordingHistories(List<Turn> turns) {
      this.turns = turns;
    }

    @Override
    public TurnHistory forAgent(AgentType agentType, AgentId agentId) {
      narrowedTo.add(agentType.value() + "/" + agentId.value());
      return this;
    }

    @Override
    public List<Turn> lastTurns(int turns) {
      windows.add(turns);
      return this.turns;
    }

    @Override
    public List<Turn> turnsFrom(long fromTurn) {
      rangeReads.add(fromTurn);
      return turns;
    }
  }

  @Test
  void turnsBecomeMessagesInOrder() {
    RecordingHistories histories =
        new RecordingHistories(List.of(turn(1, "first", "one"), turn(3, "second", "two")));

    InferenceContext context =
        new RecentTurnsContextAssembler(histories, 4096, List.of()).assemble(invocation());

    assertThat(context.turns())
        .as("handed over as turns: flattening here would pick one provider's wire shape")
        .extracting(Turn::id)
        .containsExactly(new TurnId(1), new TurnId(3));
  }

  /**
   * The identity in the invocation is the one that gets read. An assembler that narrowed to
   * anything else would build a context out of another agent's conversation, and every assertion
   * about ordering and budget would still pass.
   */
  @Test
  void itReadsTheAgentTheInvocationNames() {
    RecordingHistories histories = new RecordingHistories(List.of(turn(1, "q", "a")));

    new RecentTurnsContextAssembler(histories, 4096, List.of()).assemble(invocation());

    assertThat(histories.narrowedTo).containsExactly("chat/" + AGENT.value());
  }

  /** The budget is spent in the query, so the store is told it. */
  @Test
  void theBudgetIsHandedToTheStore() {
    RecordingHistories histories = new RecordingHistories(List.of(turn(1, "q", "a")));

    new RecentTurnsContextAssembler(histories, 1234, List.of()).assemble(invocation());

    assertThat(histories.windows).containsExactly(1234);
  }

  /**
   * Reading everything and discarding most of it is exactly what a budget exists to avoid, and it
   * is invisible in the output.
   */
  @Test
  void nothingIsReadOutsideTheBudgetedWindow() {
    RecordingHistories histories = new RecordingHistories(List.of(turn(1, "q", "a")));

    new RecentTurnsContextAssembler(histories, 4096, List.of()).assemble(invocation());

    assertThat(histories.rangeReads)
        .as("a range read means the whole story was pulled back")
        .isEmpty();
  }

  @Test
  void anEmptyHistoryAssemblesToNothingRatherThanFailing() {
    RecordingHistories histories = new RecordingHistories(List.of());

    assertThat(
            new RecentTurnsContextAssembler(histories, 4096, List.of())
                .assemble(invocation())
                .turns())
        .isEmpty();
  }

  /**
   * A budget of zero would send an empty context, and a model given no messages at all fails on two
   * of the three providers measured -- a stall that looks like a model problem.
   */
  @Test
  void aNonPositiveWindowIsRejectedWhereItIsSetRatherThanWhereItIsSpent() {
    RecordingHistories histories = new RecordingHistories(List.of());

    assertThatThrownBy(() -> new RecentTurnsContextAssembler(histories, 0, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("turns");
  }
}
