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
package org.jwcarman.nessy.engine.chapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ProseSummarizerTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final InferenceOptions OPTIONS = InferenceOptions.of("a-model");

  private static Turn turn(long id) {
    return new Turn(
        new TurnId(id),
        new Input(new Seq(id), List.of(new Block.Text("q" + id))),
        List.of(),
        new TurnResult.Answered(List.of(new Block.Text("a" + id))),
        10);
  }

  private static InferenceResult answer(String text) {
    return new InferenceResult.Answer(List.of(new Block.Text(text)), Usage.unreported());
  }

  /** A story that records which agent was asked for and which range was read. */
  private static final class Story implements TurnHistories, TurnHistory {

    private final List<Turn> turns;
    final List<String> narrowedTo = new ArrayList<>();

    Story(List<Turn> turns) {
      this.turns = turns;
    }

    @Override
    public TurnHistory forAgent(AgentType agentType, AgentId agentId) {
      narrowedTo.add(agentType.value() + "/" + agentId.value());
      return this;
    }

    @Override
    public List<Turn> turnsBetween(TurnId from, TurnId through) {
      return turns.stream()
          .filter(t -> t.id().value() >= from.value() && t.id().value() <= through.value())
          .toList();
    }

    @Override
    public List<Turn> lastTurns(int count) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Turn> turnsFrom(long fromTurn) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Turn> lastTurnsAfter(TurnId through, int count) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long turnsAfter(long through) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<TurnId> completedAfter(Optional<TurnId> through) {
      throw new UnsupportedOperationException();
    }
  }

  /** A provider that records what it was asked and answers with whatever it is scripted to. */
  private static final class Scripted implements InferenceProvider {

    final List<InferenceRequest> requests = new ArrayList<>();
    private final Function<InferenceRequest, InferenceResult> script;

    Scripted(Function<InferenceRequest, InferenceResult> script) {
      this.script = script;
    }

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      requests.add(request);
      return script.apply(request);
    }
  }

  private static Chapter chapter(long from, long through) {
    return new Chapter(TYPE, AGENT, new TurnId(from), new TurnId(through));
  }

  @Nested
  class Asking_the_model {

    @Test
    void sends_exactly_the_chapters_turns_followed_by_the_turn_that_asks() {
      Story story = new Story(List.of(turn(1), turn(3), turn(5), turn(7)));
      Scripted provider = new Scripted(_ -> answer("the record"));

      new ProseSummarizer(story, provider, OPTIONS).summarize(chapter(3, 5));

      assertThat(provider.requests).hasSize(1);
      List<Turn> sent = provider.requests.getFirst().context().turns();
      assertThat(sent).extracting(t -> t.id().value()).containsExactly(3L, 5L, 6L);
      assertThat(sent.getLast().complete()).isFalse();
      assertThat(sent.getLast().input().blocks())
          .containsExactly(new Block.Text("Write the record of everything above now."));
      assertThat(provider.requests.getFirst().context().summaries()).isEmpty();
      assertThat(provider.requests.getFirst().context().ambient()).isEmpty();
    }

    @Test
    void sends_a_one_off_since_a_chapter_is_summarised_once() {
      Story story = new Story(List.of(turn(1)));
      Scripted provider = new Scripted(_ -> answer("the record"));

      new ProseSummarizer(story, provider, OPTIONS).summarize(chapter(1, 1));

      assertThat(provider.requests).hasSize(1);
      assertThat(provider.requests.getFirst().oneOff()).isTrue();
    }

    @Test
    void sends_the_prompt_it_was_given_in_place_of_the_default() {
      Story story = new Story(List.of(turn(1)));
      Scripted provider = new Scripted(_ -> answer("the entry"));

      new ProseSummarizer(story, provider, OPTIONS, "Write an index entry.")
          .summarize(chapter(1, 1));

      assertThat(provider.requests).hasSize(1);
      assertThat(provider.requests.getFirst().systemPrompt().value())
          .isEqualTo("Write an index entry.");
    }

    @Test
    void sends_the_default_prompt_when_none_is_given() {
      Story story = new Story(List.of(turn(1)));
      Scripted provider = new Scripted(_ -> answer("the record"));

      new ProseSummarizer(story, provider, OPTIONS).summarize(chapter(1, 1));

      assertThat(provider.requests.getFirst().systemPrompt().value())
          .isEqualTo(ProseSummarizer.PROMPT);
    }

    @Test
    void reads_the_story_of_the_chapters_own_agent() {
      Story story = new Story(List.of(turn(1)));
      Scripted provider = new Scripted(_ -> answer("the record"));

      new ProseSummarizer(story, provider, OPTIONS).summarize(chapter(1, 1));

      assertThat(story.narrowedTo).containsExactly(TYPE.value() + "/" + AGENT.value());
    }

    @Test
    void uses_its_prompt_as_the_system_prompt_and_offers_no_tools() {
      Story story = new Story(List.of(turn(1)));
      Scripted provider = new Scripted(_ -> answer("the record"));

      new ProseSummarizer(story, provider, OPTIONS).summarize(chapter(1, 1));

      InferenceRequest request = provider.requests.getFirst();
      assertThat(request.systemPrompt().value()).isEqualTo(ProseSummarizer.PROMPT);
      assertThat(request.toolset().offers()).isEmpty();
      assertThat(request.options()).isEqualTo(OPTIONS);
    }
  }

  @Nested
  class The_record {

    @Test
    void is_the_text_the_model_wrote() {
      Story story = new Story(List.of(turn(1)));
      Scripted provider = new Scripted(_ -> answer("Ann owes Bob 12 euros."));

      String record = new ProseSummarizer(story, provider, OPTIONS).summarize(chapter(1, 1));

      assertThat(record).isEqualTo("Ann owes Bob 12 euros.");
    }

    @Test
    void is_returned_even_when_it_is_blank_because_the_caller_judges_that() {
      Story story = new Story(List.of(turn(1)));
      Scripted provider = new Scripted(_ -> answer("  "));

      String record = new ProseSummarizer(story, provider, OPTIONS).summarize(chapter(1, 1));

      assertThat(record).isBlank();
    }
  }

  @Nested
  class Failing {

    @Test
    void when_the_chapter_has_no_turns_it_names_the_bounds() {
      Story story = new Story(List.of(turn(1)));
      Scripted provider = new Scripted(_ -> answer("never asked"));
      ProseSummarizer summarizer = new ProseSummarizer(story, provider, OPTIONS);
      Chapter empty = chapter(4, 6);

      assertThatThrownBy(() -> summarizer.summarize(empty))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("4")
          .hasMessageContaining("6");
      assertThat(provider.requests).isEmpty();
    }

    @Test
    void when_the_model_returns_a_fault() {
      Story story = new Story(List.of(turn(1)));
      Scripted provider =
          new Scripted(
              _ ->
                  new InferenceResult.Fault(
                      new Failure.Permanent("no such model"), Usage.unreported()));
      ProseSummarizer summarizer = new ProseSummarizer(story, provider, OPTIONS);
      Chapter chapter = chapter(1, 1);

      assertThatThrownBy(() -> summarizer.summarize(chapter))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("1");
    }

    @Test
    void when_the_model_refuses() {
      Story story = new Story(List.of(turn(1)));
      Scripted provider =
          new Scripted(_ -> new InferenceResult.Refusal("safety", Usage.unreported()));
      ProseSummarizer summarizer = new ProseSummarizer(story, provider, OPTIONS);
      Chapter chapter = chapter(1, 1);

      assertThatThrownBy(() -> summarizer.summarize(chapter))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void when_the_options_are_refused_while_it_is_being_built() {
      Story story = new Story(List.of(turn(1)));
      InferenceProvider picky =
          new InferenceProvider() {
            @Override
            public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
              return answer("unused");
            }

            @Override
            public void validate(InferenceOptions options) {
              throw new IllegalArgumentException("temperature must be a number");
            }
          };

      assertThatThrownBy(() -> new ProseSummarizer(story, picky, OPTIONS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("temperature");
    }
  }
}
