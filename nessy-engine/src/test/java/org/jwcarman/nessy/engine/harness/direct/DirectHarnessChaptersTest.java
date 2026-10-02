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
package org.jwcarman.nessy.engine.harness.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryChapters;
import org.jwcarman.nessy.backend.inmemory.InMemoryLeases;
import org.jwcarman.nessy.backend.inmemory.InMemoryLocks;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.engine.chapter.ProseSummarizer;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chapters on the direct door: the keeper is built by the factory, hears turns end, and what it
 * writes shows up in the next request the model is sent.
 *
 * <p>The keeper works off the agent's thread, so a test that needs its result waits for the store
 * with a deadline rather than for a fixed time.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DirectHarnessChaptersTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final String MODEL = "the-agents-model";
  private static final Duration PATIENCE = Duration.ofSeconds(10);

  private final InMemoryChapters chapters = new InMemoryChapters();
  private final ScriptedModel model = new ScriptedModel();
  private final DefaultDirectHarnessFactory factory = factory();

  @AfterEach
  void closeFactory() {
    factory.close();
  }

  /** Answers every chat request, and keeps the chat requests apart from the summary requests. */
  private static final class ScriptedModel implements InferenceProvider {
    private final List<InferenceRequest> chats = new CopyOnWriteArrayList<>();
    private final List<InferenceRequest> summaries = new CopyOnWriteArrayList<>();

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      if (request.systemPrompt().value().equals(ProseSummarizer.PROMPT)) {
        summaries.add(request);
        return new InferenceResult.Answer(
            List.of(new Block.Text("they talked")), Usage.unreported(MODEL));
      }
      chats.add(request);
      return new InferenceResult.Answer(
          List.of(new Block.Text("answer " + chats.size())), Usage.unreported(MODEL));
    }
  }

  private DefaultDirectHarnessFactory factory() {
    return DefaultDirectHarnessFactory.of(
        f ->
            f.backend(
                    new FixedDirectBackend(
                        new InMemoryLocks(),
                        new InMemoryAgentEvents(
                            new JacksonCodecFactory(JsonMapper.builder().build())),
                        new InMemoryPayloads(new JacksonCodecFactory(JsonMapper.builder().build())),
                        chapters,
                        new InMemoryLeases()))
                .provider(ProviderId.of("test"), model)
                .schemas(new VictoolsJsonSchemaGenerator())
                .mapper(JsonMapper.builder().build()));
  }

  private DirectHarness<String, String> harness(Customizer<DirectHarnessConfig<String>> chosen) {
    return factory.<String>create(
        TYPE,
        c -> {
          c.systemPrompt("You are terse.")
              .inputRenderer(said -> List.of(new Block.Text(said)))
              .inference(in -> in.provider("test").model(MODEL));
          chosen.customize(c);
        });
  }

  private static void talk(DirectHarness<String, String> harness, AgentId agent, int turns) {
    for (int i = 1; i <= turns; i++) {
      harness.ask(agent, "turn " + i);
    }
  }

  private List<Summary> waitForSummaries(AgentId agent, int count) {
    await()
        .atMost(PATIENCE)
        .untilAsserted(
            () -> assertThat(chapters.summaries(TYPE, agent)).hasSizeGreaterThanOrEqualTo(count));
    return chapters.summaries(TYPE, agent);
  }

  private List<TurnId> completedTurns(AgentId agent) {
    return factory.histories().forAgent(TYPE, agent).completedAfter(Optional.empty());
  }

  /** Throws until it has been asked {@code failures} times, then writes. */
  private static final class FailsFirst implements Summarizer {
    private final int failures;
    private final AtomicInteger asked = new AtomicInteger();

    FailsFirst(int failures) {
      this.failures = failures;
    }

    @Override
    public String summarize(Chapter chapter) {
      if (asked.incrementAndGet() <= failures) {
        throw new IllegalStateException("the model is not answering");
      }
      return "what was said";
    }
  }

  @Nested
  @DisplayName("With chapters on")
  class WithChaptersOn {

    @Test
    void a_chapter_closes_and_its_summary_is_shown() {
      AgentId agent = AgentId.random();
      DirectHarness<String, String> harness = harness(c -> c.chapterPolicy(ChapterPolicy.every(3)));

      talk(harness, agent, 3);
      waitForSummaries(agent, 1);
      harness.ask(agent, "turn 4");

      List<TurnId> turns = completedTurns(agent);
      assertThat(turns).hasSize(4);
      InferenceRequest fourth = model.chats.getLast();
      assertThat(fourth.context().summaries()).hasSize(1);
      Chapter covered = fourth.context().summaries().getFirst().chapter();
      assertThat(covered.from()).isEqualTo(turns.get(0));
      assertThat(covered.through()).isEqualTo(turns.get(2));
      assertThat(fourth.context().turns()).hasSize(1);
      assertThat(fourth.context().turns().getFirst().id()).isEqualTo(turns.get(3));
    }

    @Test
    void turns_stay_verbatim_until_the_summary_is_written() {
      AgentId agent = AgentId.random();
      AtomicInteger asked = new AtomicInteger();
      DirectHarness<String, String> harness =
          harness(
              c ->
                  c.chapterPolicy(ChapterPolicy.every(3))
                      .summarizer(
                          chapter -> {
                            asked.incrementAndGet();
                            throw new IllegalStateException("the model is not answering");
                          }));

      talk(harness, agent, 3);
      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () -> {
                assertThat(asked).hasValueGreaterThanOrEqualTo(1);
                assertThat(chapters.closedThrough(TYPE, agent)).isPresent();
              });
      harness.ask(agent, "turn 4");

      InferenceRequest fourth = model.chats.getLast();
      assertThat(fourth.context().summaries()).isEmpty();
      assertThat(fourth.context().turns()).hasSize(4);
    }

    @Test
    void a_failed_summary_is_retried_at_a_later_turn_end() {
      AgentId agent = AgentId.random();
      FailsFirst summarizer = new FailsFirst(1);
      DirectHarness<String, String> harness =
          harness(c -> c.chapterPolicy(ChapterPolicy.every(3)).summarizer(summarizer));

      talk(harness, agent, 3);
      await().atMost(PATIENCE).until(() -> summarizer.asked.get() >= 1);

      // The attempt that failed may still be holding the lease when the next turn ends, in which
      // case that end does nothing; a turn or two later the retry has its chance.
      int next = 4;
      while (chapters.summaries(TYPE, agent).isEmpty() && next < 12) {
        harness.ask(agent, "turn " + next++);
        try {
          await()
              .atMost(Duration.ofSeconds(2))
              .until(() -> !chapters.summaries(TYPE, agent).isEmpty());
        } catch (ConditionTimeoutException stillWaiting) {
          // Another turn, another chance.
        }
      }

      List<Summary> written = waitForSummaries(agent, 1);
      assertThat(written.getFirst().text()).isEqualTo("what was said");
      assertThat(summarizer.asked).hasValueGreaterThanOrEqualTo(2);
    }

    @Test
    void the_default_summariser_uses_the_agents_own_model() {
      AgentId agent = AgentId.random();
      DirectHarness<String, String> harness = harness(c -> c.chapterPolicy(ChapterPolicy.every(3)));

      talk(harness, agent, 3);
      List<Summary> written = waitForSummaries(agent, 1);

      assertThat(written.getFirst().text()).isEqualTo("they talked");
      assertThat(model.summaries).isNotEmpty();
      assertThat(model.summaries.getFirst().options().modelName()).isEqualTo(MODEL);
    }
  }

  @Nested
  @DisplayName("Without chapters")
  class WithoutChapters {

    @Test
    void nothing_is_cut_without_chapters() {
      AgentId agent = AgentId.random();
      DirectHarness<String, String> harness =
          harness(c -> c.inference(in -> in.context(ctx -> ctx.withoutChapters().maxTail(50))));

      talk(harness, agent, 25);

      assertThat(chapters.closedThrough(TYPE, agent)).isEmpty();
      assertThat(chapters.unsummarized(TYPE, agent)).isEmpty();
      assertThat(chapters.summaries(TYPE, agent)).isEmpty();
      assertThat(model.summaries).isEmpty();
      assertThat(model.chats).hasSize(25);
      assertThat(model.chats.getLast().context().turns()).hasSize(25);
    }
  }

  @Nested
  @DisplayName("Chapters already stored")
  class Stored {

    @Test
    void summaries_in_the_store_are_ignored_by_a_harness_without_chapters() {
      AgentId agent = AgentId.random();
      DirectHarness<String, String> harness = harness(c -> c.chapterPolicy(ChapterPolicy.every(3)));
      talk(harness, agent, 3);
      waitForSummaries(agent, 1);
      DirectHarness<String, String> without =
          harness(c -> c.inference(in -> in.context(ctx -> ctx.withoutChapters())));

      without.ask(agent, "turn 4");

      InferenceRequest request = model.chats.getLast();
      assertThat(request.context().summaries()).isEmpty();
      assertThat(request.context().turns()).hasSize(4);
      assertThat(chapters.summaries(TYPE, agent)).isNotEmpty();
    }
  }

  @Nested
  @DisplayName("Building a harness")
  class Building {

    @Test
    void max_tail_must_exceed_the_maximum_chapter_length() {
      Customizer<DirectHarnessConfig<String>> tooShort =
          c -> c.inference(in -> in.context(ctx -> ctx.maxTail(10).maxChapterLength(10)));

      assertThatThrownBy(() -> harness(tooShort))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("maxTail (10)")
          .hasMessageContaining("maxChapterLength (10)");
    }
  }
}
