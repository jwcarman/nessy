/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Memory;
import org.jwcarman.nessy.api.MemorySource;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.State;
import org.jwcarman.nessy.api.StateSource;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.backend.inmemory.InMemoryChapters;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tail, then background -- and what an assembler ASKS FOR matters as much as what it returns.
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
        new Input(new Seq(id), List.of(new Block.Text("q" + id))),
        List.of(),
        new TurnResult.Answered(List.of(new Block.Text("a" + id))));
  }

  private static List<Turn> turns(long from, long through) {
    return IntStream.rangeClosed((int) from, (int) through).mapToObj(i -> turn(i)).toList();
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
    public List<Turn> turnsBetween(TurnId from, TurnId through) {
      return story.stream()
          .filter(t -> t.id().value() >= from.value() && t.id().value() <= through.value())
          .toList();
    }

    @Override
    public List<TurnId> completedAfter(Optional<TurnId> through) {
      return story.stream()
          .filter(Turn::complete)
          .map(Turn::id)
          .filter(id -> through.isEmpty() || id.value() > through.get().value())
          .toList();
    }
  }

  private static ContextAssembler assembler(TurnHistories histories, int maxTail) {
    return new ContextAssembler(histories, maxTail, List.of(), List.of(), List.of());
  }

  @Nested
  class The_tail {

    @Test
    void the_tail_is_the_last_maxTail_turns_of_the_whole_story() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));

      InferenceContext context = assembler(histories, 5).assemble(invocation());

      assertThat(context.summaries()).isEmpty();
      assertThat(ids(context.tail())).containsExactly(25L, 26L, 27L, 28L, 29L);
      assertThat(context.activeTurn().id()).isEqualTo(new TurnId(30));
    }

    /** The cap is spent in the query, so the store is told it. */
    @Test
    void the_cap_is_handed_to_the_store_rather_than_applied_after_reading_everything() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));

      assembler(histories, 5).assemble(invocation());

      assertThat(histories.windows)
          .as("the active turn is read besides the tail")
          .containsExactly(6);
      assertThat(histories.tailsAfter)
          .as("no range read: the whole story was not pulled back")
          .isEmpty();
    }

    @Test
    void an_empty_story_has_no_turn_to_answer() {
      ContextAssembler assembler = assembler(new RecordingHistories(List.of()), 5);
      InferenceInvocation invocation = invocation();

      assertThatThrownBy(() -> assembler.assemble(invocation))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no turn to answer");
    }

    @Test
    void the_active_turn_is_the_newest_and_is_not_in_the_tail() {
      RecordingHistories histories = new RecordingHistories(turns(1, 3));

      InferenceContext context = assembler(histories, 5).assemble(invocation());

      assertThat(context.activeTurn().id()).isEqualTo(new TurnId(3));
      assertThat(ids(context.tail())).containsExactly(1L, 2L);
      assertThat(ids(context.turns())).containsExactly(1L, 2L, 3L);
    }

    @Test
    void a_story_of_one_turn_has_an_empty_tail() {
      RecordingHistories histories = new RecordingHistories(turns(1, 1));

      InferenceContext context = assembler(histories, 5).assemble(invocation());

      assertThat(context.tail()).isEmpty();
      assertThat(context.activeTurn().id()).isEqualTo(new TurnId(1));
    }
  }

  /** Chapters closed over turns 1..10 and 11..20, with summaries as given. */
  private static InMemoryChapters closed(int summarised) {
    InMemoryChapters chapters =
        new InMemoryChapters(new JacksonCodecFactory(JsonMapper.builder().build()));
    Chapter first = new Chapter(TYPE, AGENT, new TurnId(1), new TurnId(10));
    Chapter second = new Chapter(TYPE, AGENT, new TurnId(11), new TurnId(20));
    chapters.append(TYPE, AGENT, Optional.empty(), List.of(first, second));
    if (summarised >= 1) {
      chapters.summarize(new Summary(first, "the first ten"));
    }
    if (summarised >= 2) {
      chapters.summarize(new Summary(second, "the second ten"));
    }
    return chapters;
  }

  @Nested
  class With_chapters {

    @Test
    void the_summaries_come_first_and_the_tail_begins_after_the_last_of_them() {
      RecordingHistories histories = new RecordingHistories(turns(1, 25));

      InferenceContext context =
          new ContextAssembler(histories, closed(2), 40, List.of(), List.of(), List.of())
              .assemble(invocation());

      assertThat(context.summaries())
          .extracting(Summary::text)
          .containsExactly("the first ten", "the second ten");
      assertThat(ids(context.tail())).containsExactly(21L, 22L, 23L, 24L);
      assertThat(context.activeTurn().id()).isEqualTo(new TurnId(25));
      assertThat(histories.tailsAfter).containsExactly(20L);
    }

    @Test
    void an_unwritten_summary_leaves_its_turns_in_the_tail() {
      RecordingHistories histories = new RecordingHistories(turns(1, 25));

      InferenceContext context =
          new ContextAssembler(histories, closed(1), 40, List.of(), List.of(), List.of())
              .assemble(invocation());

      assertThat(context.summaries()).extracting(Summary::text).containsExactly("the first ten");
      assertThat(ids(context.turns())).hasSize(15).startsWith(11L).endsWith(25L);
    }

    @Test
    void the_tail_after_a_summary_is_capped() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));

      InferenceContext context =
          new ContextAssembler(histories, closed(2), 3, List.of(), List.of(), List.of())
              .assemble(invocation());

      assertThat(ids(context.tail())).containsExactly(27L, 28L, 29L);
      assertThat(context.activeTurn().id()).isEqualTo(new TurnId(30));
      assertThat(histories.tailCaps).containsExactly(4);
    }

    @Test
    void with_no_summary_the_tail_is_the_last_maxTail_turns() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));

      InferenceContext context =
          new ContextAssembler(
                  histories,
                  new InMemoryChapters(new JacksonCodecFactory(JsonMapper.builder().build())),
                  5,
                  List.of(),
                  List.of(),
                  List.of())
              .assemble(invocation());

      assertThat(context.summaries()).isEmpty();
      assertThat(ids(context.tail())).containsExactly(25L, 26L, 27L, 28L, 29L);
      assertThat(context.activeTurn().id()).isEqualTo(new TurnId(30));
    }

    @Test
    void a_summary_that_reaches_the_turn_being_answered_is_refused() {
      RecordingHistories histories = new RecordingHistories(turns(1, 20));
      ContextAssembler assembler =
          new ContextAssembler(histories, closed(2), 40, List.of(), List.of(), List.of());
      InferenceInvocation invocation = invocation();

      assertThatThrownBy(() -> assembler.assemble(invocation))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "a summary reaches the turn being answered: nothing is left after turn "
                  + new TurnId(20));
    }
  }

  @Test
  void without_chapters_the_stored_summaries_are_not_read_and_the_story_is_cut_at_the_cap() {
    RecordingHistories histories = new RecordingHistories(turns(1, 30));
    ContextAssembler assembler =
        new ContextAssembler(histories, null, 5, List.of(), List.of(), List.of());

    InferenceContext context = assembler.assemble(invocation());

    assertThat(context.summaries()).isEmpty();
    assertThat(ids(context.tail())).containsExactly(25L, 26L, 27L, 28L, 29L);
    assertThat(context.activeTurn().id()).isEqualTo(new TurnId(30));
  }

  @Test
  void it_reads_the_agent_the_invocation_names() {
    RecordingHistories histories = new RecordingHistories(turns(1, 3));

    assembler(histories, 5).assemble(invocation());

    assertThat(histories.narrowedTo).containsExactly("chat/" + AGENT.value());
  }

  @Test
  void ambient_is_gathered_for_the_same_agent_and_rides_along_after_the_story() {
    RecordingHistories histories = new RecordingHistories(turns(1, 3));
    List<AgentId> askedFor = new ArrayList<>();
    AmbientSource clock =
        AmbientSource.of(
            source ->
                source
                    .kind("clock")
                    .text(
                        who -> {
                          askedFor.add(who);
                          return Optional.of("it is Tuesday");
                        }));

    InferenceContext context =
        new ContextAssembler(histories, 5, List.of(), List.of(), List.of(clock))
            .assemble(invocation());

    assertThat(askedFor).containsExactly(AGENT);
    assertThat(context.ambient()).extracting(Ambient::kind).containsExactly("clock");
  }

  /** Records what each source was handed, and in what order the sources were asked. */
  private static final class Asked {
    final List<String> order = new ArrayList<>();
    final List<Turn> handed = new ArrayList<>();
  }

  private static MemorySource memorySource(String kind, Asked asked) {
    return new MemorySource() {
      @Override
      public String kind() {
        return kind;
      }

      @Override
      public Optional<Memory> forAgent(AgentId agentId, Turn current) {
        asked.order.add("memory " + kind);
        asked.handed.add(current);
        return Optional.of(Memory.text(kind, "recalled " + kind));
      }
    };
  }

  private static StateSource stateSource(String kind, Asked asked) {
    return new StateSource() {
      @Override
      public String kind() {
        return kind;
      }

      @Override
      public Optional<State> forAgent(AgentId agentId, Turn current) {
        asked.order.add("state " + kind);
        asked.handed.add(current);
        return Optional.of(State.text(kind, "standing " + kind));
      }
    };
  }

  @Nested
  class Memory_and_state {

    @Test
    void each_source_is_handed_the_active_turn() {
      RecordingHistories histories = new RecordingHistories(turns(1, 9));
      Asked asked = new Asked();
      ContextAssembler assembler =
          new ContextAssembler(
              histories,
              5,
              List.of(memorySource("episodes", asked)),
              List.of(stateSource("plan", asked)),
              List.of());

      InferenceContext context = assembler.assemble(invocation());

      assertThat(asked.handed)
          .hasSize(2)
          .allSatisfy(turn -> assertThat(turn.id()).isEqualTo(new TurnId(9)));
      assertThat(context.memory()).containsExactly(Memory.text("episodes", "recalled episodes"));
      assertThat(context.state()).containsExactly(State.text("plan", "standing plan"));
    }

    @Test
    void sources_are_asked_in_the_order_they_were_bound_memory_then_state_then_ambient() {
      RecordingHistories histories = new RecordingHistories(turns(1, 3));
      Asked asked = new Asked();
      AmbientSource clock =
          AmbientSource.of(
              source ->
                  source
                      .kind("clock")
                      .text(
                          _ -> {
                            asked.order.add("ambient clock");
                            return Optional.of("Tuesday");
                          }));
      ContextAssembler assembler =
          new ContextAssembler(
              histories,
              5,
              List.of(memorySource("second", asked), memorySource("first", asked)),
              List.of(stateSource("b", asked), stateSource("a", asked)),
              List.of(clock));

      InferenceContext context = assembler.assemble(invocation());

      assertThat(asked.order)
          .containsExactly("memory second", "memory first", "state b", "state a", "ambient clock");
      assertThat(context.memory()).extracting(Memory::kind).containsExactly("second", "first");
      assertThat(context.state()).extracting(State::kind).containsExactly("b", "a");
    }

    @Test
    void a_source_with_nothing_to_say_is_left_out() {
      RecordingHistories histories = new RecordingHistories(turns(1, 3));
      MemorySource quiet = MemorySource.constant(Memory.text("episodes", "x"));
      MemorySource silent =
          new MemorySource() {
            @Override
            public String kind() {
              return "silent";
            }

            @Override
            public Optional<Memory> forAgent(AgentId agentId, Turn current) {
              return Optional.empty();
            }
          };

      InferenceContext context =
          new ContextAssembler(histories, 5, List.of(silent, quiet), List.of(), List.of())
              .assemble(invocation());

      assertThat(context.memory()).extracting(Memory::kind).containsExactly("episodes");
    }
  }

  /** A cap of zero would send an empty context; refused where it is set, not where it is spent. */
  @Test
  void a_non_positive_cap_is_rejected_at_construction() {
    RecordingHistories empty = new RecordingHistories(List.of());
    assertThatThrownBy(() -> assembler(empty, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxTail");
  }
}
