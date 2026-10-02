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
import org.jwcarman.nessy.api.QueuedHarness;
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

    // This door tells a listener a turn ended inside the transaction that ended it, so the keeper
    // can run before that turn is readable and sees it only at the next turn's end: the chapter
    // over turns one to three closes when the fifth ends.
    for (int turn = 1; turn <= 4; turn++) {
      tellAndWait(harness, agent, turn);
    }
    await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(engine.chapters().summaries(TYPE, agent)).hasSize(1));
    tellAndWait(harness, agent, 5);

    List<TurnId> turns = engine.history().forAgent(TYPE, agent).completedAfter(Optional.empty());
    InferenceRequest fifth = chats.getLast();
    assertThat(fifth.context().summaries()).hasSize(1);
    Summary summary = fifth.context().summaries().getFirst();
    Chapter covered = summary.chapter();
    assertThat(summary.text()).isEqualTo("they talked");
    assertThat(covered.from()).isEqualTo(turns.get(0));
    assertThat(covered.through()).isEqualTo(turns.get(2));
    assertThat(fifth.context().turns()).hasSize(2);
    assertThat(fifth.context().turns().getFirst().id()).isEqualTo(turns.get(3));
  }
}
