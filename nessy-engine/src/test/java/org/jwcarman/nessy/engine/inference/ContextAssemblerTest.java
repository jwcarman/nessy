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
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;

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
        new TurnResult.Answered(List.of(new Block.Text("a" + id))),
        10);
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
  }

  private static ContextAssembler assembler(TurnHistories histories, int maxTail) {
    return new ContextAssembler(histories, maxTail, List.of());
  }

  @Nested
  class The_tail {

    @Test
    void the_tail_is_the_last_maxTail_turns_of_the_whole_story() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));

      InferenceContext context = assembler(histories, 5).assemble(invocation());

      assertThat(context.summaries()).isEmpty();
      assertThat(ids(context.turns())).containsExactly(26L, 27L, 28L, 29L, 30L);
    }

    /** The cap is spent in the query, so the store is told it. */
    @Test
    void the_cap_is_handed_to_the_store_rather_than_applied_after_reading_everything() {
      RecordingHistories histories = new RecordingHistories(turns(1, 30));

      assembler(histories, 5).assemble(invocation());

      assertThat(histories.windows).containsExactly(5);
      assertThat(histories.tailsAfter)
          .as("no range read: the whole story was not pulled back")
          .isEmpty();
    }

    @Test
    void an_empty_story_assembles_to_nothing_rather_than_failing() {
      InferenceContext context =
          assembler(new RecordingHistories(List.of()), 5).assemble(invocation());

      assertThat(context.turns()).isEmpty();
    }
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
        new ContextAssembler(histories, 5, List.of(clock)).assemble(invocation());

    assertThat(askedFor).containsExactly(AGENT);
    assertThat(context.ambient()).extracting(Ambient::kind).containsExactly("clock");
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
