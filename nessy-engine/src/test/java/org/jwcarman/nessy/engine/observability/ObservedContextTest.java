package org.jwcarman.nessy.engine.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;

/**
 * What building a request costs, said in spans by the pieces themselves.
 *
 * <p>Each decorator is what the harness wraps around what it was handed, so these assertions are
 * what a trace shows: a history read, a memory search with how much it found, an ambient source
 * named for what it returned, and the two that bracket them.
 */
class ObservedContextTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());

  private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
  private final ObservationRegistry registry = ObservationRegistry.create();

  ObservedContextTest() {
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              @Override
              public void onStop(Observation.Context context) {
                stopped.add(context);
              }

              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }
            });
  }

  private Observation.Context only() {
    assertThat(stopped).hasSize(1);
    return stopped.getFirst();
  }

  @Test
  void a_summary_source_is_read_as_semconvs_search_memory() {
    Summarizer source = _ -> List.of(Summary.text(new TurnId(1), new TurnId(4), "turns 1-4"));

    ObservedSummarizer.wrap(source, registry).forAgent(AGENT);

    assertThat(only().getContextualName()).isEqualTo("search_memory");
    assertThat(only().getLowCardinalityKeyValue("gen_ai.operation.name").getValue())
        .isEqualTo("search_memory");
    assertThat(only().getHighCardinalityKeyValue("gen_ai.memory.record.count").getValue())
        .isEqualTo("1");
  }

  @Test
  void an_ambient_source_is_named_for_what_it_returned() {
    AmbientSource clock =
        AmbientSource.of(a -> a.kind("clock").text(_ -> Optional.of("it is Tuesday")));

    ObservedAmbientSource.wrap(clock, registry).forAgent(AGENT);

    assertThat(only().getContextualName()).isEqualTo("nessy.context ambient clock");
  }

  /**
   * The case worth seeing: a source that cost time and offered nothing is as identifiable as one
   * that offered something, because the name comes from the source rather than from its answer.
   */
  @Test
  void a_source_that_offers_nothing_is_named_all_the_same() {
    AmbientSource silent = AmbientSource.of(a -> a.kind("silent").text(_ -> Optional.empty()));

    ObservedAmbientSource.wrap(silent, registry).forAgent(AGENT);

    assertThat(only().getContextualName()).isEqualTo("nessy.context ambient silent");
  }

  @Test
  void assembling_a_context_is_one_span_over_the_reads() {
    ObservedInferenceContextAssembler.wrap(
            _ -> new InferenceContext(List.of(), List.of(), List.of()), registry)
        .assemble(
            new org.jwcarman.nessy.engine.inference.InferenceInvocation(
                TYPE, AGENT, InferenceOptions.of("a-model")));

    assertThat(only().getContextualName()).isEqualTo("nessy.context");
    assertThat(only().getLowCardinalityKeyValue(Identity.AGENT_NAME).getValue()).isEqualTo("chat");
  }

  @Test
  void writing_down_what_the_model_was_shown_is_its_own_span() {
    ObservedInferenceRecorder.wrap(
            org.jwcarman.nessy.engine.inference.InferenceRecorder.NONE, registry)
        .begin(TYPE, AGENT, null);

    assertThat(only().getContextualName()).isEqualTo("nessy.record");
  }

  /** Reading the tail says how much of the story came back, which grows with the conversation. */
  @Test
  void reading_the_tail_says_how_many_turns_it_found() {
    ObservedTurnHistories.wrap((_, _) -> new StoryOfOne(), registry)
        .forAgent(TYPE, AGENT)
        .lastTurns(20);

    assertThat(only().getContextualName()).isEqualTo("nessy.context history");
    assertThat(only().getHighCardinalityKeyValue("nessy.context.turns").getValue()).isEqualTo("1");
  }

  /** Every way of reading the story is timed, not just the one the assembler happens to use. */
  @Test
  void every_read_of_the_story_is_a_span() {
    org.jwcarman.nessy.engine.store.TurnHistory history =
        ObservedTurnHistories.wrap((_, _) -> new StoryOfOne(), registry).forAgent(TYPE, AGENT);

    history.turnsFrom(1);
    history.lastTurnsAfter(new TurnId(1), 5);

    assertThat(stopped)
        .extracting(Observation.Context::getContextualName)
        .containsExactly("nessy.context history", "nessy.context history");
  }

  /** Counting is not reading: nothing of the story comes back, so nothing is timed. */
  @Test
  void counting_what_is_left_is_not_a_read() {
    ObservedTurnHistories.wrap((_, _) -> new StoryOfOne(), registry)
        .forAgent(TYPE, AGENT)
        .turnsAfter(1);

    assertThat(stopped).isEmpty();
  }

  /**
   * A source asked outside any span still reports: whose work it is simply goes unsaid, rather than
   * the span going missing.
   */
  @Test
  void a_read_outside_an_agents_span_is_still_a_span() {
    ObservedAmbientSource.wrap(
            AmbientSource.of(a -> a.kind("clock").text(_ -> Optional.of("it is Tuesday"))),
            registry)
        .forAgent(AGENT);

    assertThat(only().getContextualName()).isEqualTo("nessy.context ambient clock");
    assertThat(only().getLowCardinalityKeyValue(Identity.AGENT_NAME)).isNull();
  }

  /** One turn, so that "how many came back" has an answer worth asserting. */
  private static final class StoryOfOne implements org.jwcarman.nessy.engine.store.TurnHistory {

    private static final org.jwcarman.nessy.api.turn.Turn TURN =
        new org.jwcarman.nessy.api.turn.Turn(
            new TurnId(1),
            new org.jwcarman.nessy.api.turn.Observation(
                new Seq(1), List.<Block.ObservationContent>of(new Block.Text("hello"))),
            List.of(),
            null,
            0);

    @Override
    public List<org.jwcarman.nessy.api.turn.Turn> lastTurns(int turns) {
      return List.of(TURN);
    }

    @Override
    public List<org.jwcarman.nessy.api.turn.Turn> turnsFrom(long fromTurn) {
      return List.of(TURN);
    }

    @Override
    public List<org.jwcarman.nessy.api.turn.Turn> lastTurnsAfter(TurnId through, int turns) {
      return List.of(TURN);
    }

    @Override
    public long turnsAfter(long through) {
      return 1;
    }
  }
}
