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
package org.jwcarman.nessy.engine.harness.queued;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.QueuedHarnessConfig;
import org.jwcarman.nessy.api.QueuedHarnessFactory;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.chapter.ProseSummarizer;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * Chapters on the queued door, over a real PostgreSQL: the factory builds the keeper, a turn ending
 * wakes it, and the summary it writes is what the next request is sent.
 */
@Tag("container")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class QueuedHarnessChaptersTest {

  private static final AgentType TYPE = new AgentType("chaptered");
  private static final Duration PATIENCE = Duration.ofSeconds(30);

  private final List<InferenceRequest> chats = new CopyOnWriteArrayList<>();
  private final EngineFixture engine =
      new EngineFixture(
          (request, narrator) -> {
            if (request.systemPrompt().value().equals(ProseSummarizer.PROMPT)) {
              return new InferenceResult.Answer(
                  List.of(new Block.Text("they talked")), Usage.unreported("a-model"));
            }
            chats.add(request);
            return new InferenceResult.Answer(
                List.of(new Block.Text("answer " + chats.size())), Usage.unreported("a-model"));
          });

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  private void tellAndWait(QueuedHarness<String> harness, AgentId agent, int turn) {
    harness.tell(agent, "turn " + turn);
    await()
        .atMost(PATIENCE)
        .untilAsserted(
            () ->
                assertThat(engine.history().forAgent(TYPE, agent).completedAfter(Optional.empty()))
                    .hasSize(turn));
  }

  @Test
  void a_chapter_closes_and_its_summary_is_shown() {
    AgentId agent = AgentId.random();
    QueuedHarness<String> harness =
        engine
            .harnesses()
            .create(
                TYPE,
                String.class,
                c ->
                    c.systemPrompt("You are terse.")
                        .chapterPolicy(ChapterPolicy.every(3))
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));

    for (int turn = 1; turn <= 3; turn++) {
      tellAndWait(harness, agent, turn);
    }
    await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(engine.chapters().summaries(TYPE, agent)).hasSize(1));
    tellAndWait(harness, agent, 4);

    List<TurnId> turns = engine.history().forAgent(TYPE, agent).completedAfter(Optional.empty());
    InferenceRequest fourth = chats.getLast();
    assertThat(fourth.context().summaries()).hasSize(1);
    Summary summary = fourth.context().summaries().getFirst();
    Chapter covered = summary.chapter();
    assertThat(summary.text()).isEqualTo("they talked");
    assertThat(covered.from()).isEqualTo(turns.get(0));
    assertThat(covered.through()).isEqualTo(turns.get(2));
    assertThat(fourth.context().turns()).hasSize(1);
    assertThat(fourth.context().turns().getFirst().id()).isEqualTo(turns.get(3));
  }

  @Test
  void max_tail_must_exceed_the_maximum_chapter_length() {
    Customizer<QueuedHarnessConfig<String>> tooShort =
        c -> c.inference(in -> in.context(ctx -> ctx.maxTail(10).maxChapterLength(10)));
    QueuedHarnessFactory harnesses = engine.harnesses();

    assertThatThrownBy(() -> harnesses.create(new AgentType("too-short"), String.class, tooShort))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("maxTail (10)")
        .hasMessageContaining("maxChapterLength (10)");
  }

  @Test
  void a_short_tail_is_allowed_without_chapters() {
    Customizer<QueuedHarnessConfig<String>> noChapters =
        c ->
            c.systemPrompt("You are terse.")
                .inference(in -> in.context(ctx -> ctx.withoutChapters().maxTail(10)));
    QueuedHarnessFactory harnesses = engine.harnesses();

    assertThatCode(() -> harnesses.create(new AgentType("no-chapters"), String.class, noChapters))
        .doesNotThrowAnyException();
  }
}
