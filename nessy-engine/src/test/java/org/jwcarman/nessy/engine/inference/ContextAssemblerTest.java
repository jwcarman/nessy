package org.jwcarman.nessy.engine.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SummarySource;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;

/**
 * Summaries, then the tail, then background -- and what an assembler ASKS FOR matters as much as
 * what it returns.
 *
 * <p>An assembler that read the whole story and threw most of it away would pass any test written
 * only against its output while defeating the point of the cap. So the stub records the requests,
 * not just the answers: which agent, which window, which range.
 */
class ContextAssemblerTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());

  private static Turn turn(long id) {
    return new Turn(
        new TurnId(id),
        new Observation(new Seq(id), HistoryEntry.ObservationReceived.text("q" + id)),
        List.of(),
        new TurnResult.Answered(HistoryEntry.InferenceAnswered.text("a" + id)),
        10);
  }

  private static List<Turn> turns(long from, long through) {
    return IntStream.rangeClosed((int) from, (int) through).mapToObj(i -> turn(i)).toList();
  }

  private static Summary summary(long from, long through) {
    return Summary.text(
        new TurnId(from), new TurnId(through), "turns %d-%d".formatted(from, through));
  }

  private static InferenceInvocation invocation() {
    return new InferenceInvocation(TYPE, AGENT, InferenceOptions.of("a-model"));
  }

  private static List<Long> ids(List<Turn> turns) {
    return turns.stream().map(t -> t.id().value()).toList();
  }

  /** A story that records which agent was narrowed to and what was asked of it. */
  static final class RecordingHistories implements TurnHistories, TurnHistory {

    private final List<Turn> story;
    final List<String> narrowedTo = new ArrayList<>();
    final List<Integer> windows = new ArrayList<>();
    final List<Long> tailsAfter = new ArrayList<>();
    final List<Integer> tailCaps = new ArrayList<>();

    RecordingHistories(List<Turn> story) {
      this.story = story;
    }

    @Override
    public TurnHistory forAgent(AgentType agentType, AgentId agentId) {
      narrowedTo.add(agentType.value() + "/" + agentId.value());
      return this;
    }

    @Override
    public List<Turn> lastTurns(int turns) {
      windows.add(turns);
      return story.size() <= turns ? story : story.subList(story.size() - turns, story.size());
    }

    @Override
    public List<Turn> turnsFrom(long fromTurn) {
      return story.stream().filter(t -> t.id().value() >= fromTurn).toList();
    }

    @Override
    public List<Turn> lastTurnsAfter(TurnId through, int turns) {
      tailsAfter.add(through.value());
      tailCaps.add(turns);
      List<Turn> after = turnsFrom(through.value() + 1);
      return after.size() <= turns ? after : after.subList(after.size() - turns, after.size());
    }

    @Override
    public long turnsAfter(long through) {
      return turnsFrom(through + 1).size();
    }

    @Override
    public List<AgentId> agents(AgentType agentType) {
      return List.of();
    }
  }

  /**
   * A source that has summarised further than it chooses to show: the tail must begin after what it
   * has summarised, not after what it showed, or the head it summarised comes back as turns.
   */
  @Test
  void theTailBeginsAfterWhatWasSummarisedNotAfterWhatWasShown() {
    RecordingHistories histories = new RecordingHistories(turns(1, 12));
    SummarySource selective =
        new SummarySource() {
          @Override
          public List<Summary> forAgent(AgentId agentId) {
            // Shows only the first of its two summaries, as a relevance filter might.
            return List.of(Summary.text(new TurnId(1), new TurnId(4), "the beginning"));
          }

          @Override
          public Optional<TurnId> summarizedThrough(AgentId agentId) {
            return Optional.of(new TurnId(8));
          }
        };

    InferenceContext context = assembler(histories, List.of(selective), 20).assemble(invocation());

    assertThat(context.summaries()).hasSize(1);
    assertThat(ids(context.turns())).containsExactly(9L, 10L, 11L, 12L);
    assertThat(histories.tailsAfter).containsExactly(8L);
  }

  private static ContextAssembler assembler(
      TurnHistories histories, List<SummarySource> summaries, int maxTail) {
    return new ContextAssembler(histories, summaries, maxTail, List.of());
  }

  @Nested
  class WithNoSummaries {

    @Test
    void the_tail_is_the_last_maxTail_turns_of_the_whole_story() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));

      InferenceContext context = assembler(histories, List.of(), 5).assemble(invocation());

      assertThat(context.summaries()).isEmpty();
      assertThat(ids(context.turns())).containsExactly(26L, 27L, 28L, 29L, 30L);
    }

    /** The cap is spent in the query, so the store is told it. */
    @Test
    void the_cap_is_handed_to_the_store_rather_than_applied_after_reading_everything() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));

      assembler(histories, List.of(), 5).assemble(invocation());

      assertThat(histories.windows).containsExactly(5);
      assertThat(histories.tailsAfter)
          .as("no range read: the whole story was not pulled back")
          .isEmpty();
    }

    @Test
    void an_empty_story_assembles_to_nothing_rather_than_failing() {
      InferenceContext context =
          assembler(new RecordingHistories(List.of()), List.of(), 5).assemble(invocation());

      assertThat(context.turns()).isEmpty();
    }
  }

  @Nested
  class WithSummaries {

    @Test
    void the_summaries_come_first_and_the_tail_is_everything_after_the_last_one() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));
      SummarySource source = _ -> List.of(summary(1, 10), summary(11, 20));

      InferenceContext context = assembler(histories, List.of(source), 50).assemble(invocation());

      assertThat(context.summaries())
          .extracting(s -> s.through().value())
          .containsExactly(10L, 20L);
      assertThat(ids(context.turns()))
          .containsExactly(21L, 22L, 23L, 24L, 25L, 26L, 27L, 28L, 29L, 30L);
      assertThat(histories.tailsAfter)
          .as("read as a tail from the last summary")
          .containsExactly(20L);
      assertThat(histories.tailCaps).as("with the cap handed to the store").containsExactly(50);
      assertThat(histories.windows).as("and never as an uncapped window").isEmpty();
    }

    /**
     * <b>A gap between summaries is not filled.</b> A source that returns only the relevant
     * episodes has left the others out on purpose; filling in would load every turn it declined to
     * summarise and make the context larger, not smaller.
     */
    @Test
    void a_gap_between_summaries_is_left_out_rather_than_filled_with_turns() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));
      SummarySource relevant = _ -> List.of(summary(1, 10), summary(21, 25));

      InferenceContext context = assembler(histories, List.of(relevant), 50).assemble(invocation());

      assertThat(ids(context.turns()))
          .as("turns 11-20 are neither summarised nor sent")
          .containsExactly(26L, 27L, 28L, 29L, 30L);
    }

    @Test
    void the_tail_is_still_capped_at_maxTail() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));
      SummarySource source = _ -> List.of(summary(1, 10));

      InferenceContext context = assembler(histories, List.of(source), 3).assemble(invocation());

      assertThat(ids(context.turns())).containsExactly(28L, 29L, 30L);
    }

    @Test
    void several_sources_are_concatenated_in_the_order_they_were_added() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));
      SummarySource episodes = _ -> List.of(summary(1, 10));
      SummarySource folded = _ -> List.of(summary(11, 20));

      InferenceContext context =
          assembler(histories, List.of(episodes, folded), 50).assemble(invocation());

      assertThat(context.summaries()).extracting(s -> s.from().value()).containsExactly(1L, 11L);
    }

    /** The same turns twice, in two forms, is a confused source rather than a selective one. */
    @Test
    void summaries_that_overlap_are_refused() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));
      SummarySource confused = _ -> List.of(summary(1, 15), summary(10, 20));

      assertThatThrownBy(() -> assembler(histories, List.of(confused), 50).assemble(invocation()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("overlap");
    }

    /**
     * The turn being answered is always the newest one and is always in the story by the time this
     * runs, so a summary that reaches it leaves nothing after it. That is a source summarising the
     * question before it has been answered, and the model must not be asked to reply to a recap.
     */
    @Test
    void a_summary_that_reaches_the_turn_in_flight_is_refused() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));
      SummarySource tooEager = _ -> List.of(summary(1, 30));

      assertThatThrownBy(() -> assembler(histories, List.of(tooEager), 50).assemble(invocation()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("turn being answered");
    }
  }

  @Test
  void it_reads_the_agent_the_invocation_names() {
    RecordingHistories histories = new RecordingHistories(turns(1, 3));

    assembler(histories, List.of(), 5).assemble(invocation());

    assertThat(histories.narrowedTo).containsExactly("chat/" + AGENT.value());
  }

  @Test
  void ambient_is_gathered_for_the_same_agent_and_rides_along_after_the_story() {
    RecordingHistories histories = new RecordingHistories(turns(1, 3));
    List<AgentId> askedFor = new ArrayList<>();
    AmbientSource clock =
        who -> {
          askedFor.add(who);
          return Optional.of(Ambient.text("clock", "it is Tuesday"));
        };

    InferenceContext context =
        new ContextAssembler(histories, List.of(), 5, List.of(clock)).assemble(invocation());

    assertThat(askedFor).containsExactly(AGENT);
    assertThat(context.ambient()).extracting(Ambient::kind).containsExactly("clock");
  }

  /** A cap of zero would send an empty context; refused where it is set, not where it is spent. */
  @Test
  void a_non_positive_cap_is_rejected_at_construction() {
    assertThatThrownBy(() -> assembler(new RecordingHistories(List.of()), List.of(), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxTail");
  }
}
