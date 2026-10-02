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
package org.jwcarman.nessy.engine.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.OpenTurns;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Chapter;

/** What the summariser and the chapter policy report when the keeper asks them something. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObservedChaptersTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final Chapter CHAPTER = new Chapter(TYPE, AGENT, new TurnId(1), new TurnId(3));

  private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
  private final ObservationRegistry registry = ObservationRegistry.create();

  ObservedChaptersTest() {
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
  void a_summary_is_a_span_named_for_the_work_and_tagged_with_whose_it_was() {
    Summarizer observed = ObservedSummarizer.wrap(chapter -> "they talked", registry);

    String text = observed.summarize(CHAPTER);

    assertThat(text).isEqualTo("they talked");
    Observation.Context span = only();
    assertThat(span.getName()).isEqualTo("nessy.summary");
    assertThat(span.getLowCardinalityKeyValue(Identity.AGENT_NAME).getValue()).isEqualTo("chat");
    assertThat(span.getHighCardinalityKeyValue("nessy.summary.through").getValue()).isEqualTo("3");
  }

  @Test
  void a_summariser_that_throws_reports_the_error_and_still_throws() {
    Summarizer observed =
        ObservedSummarizer.wrap(
            chapter -> {
              throw new IllegalStateException("no answer");
            },
            registry);

    try {
      observed.summarize(CHAPTER);
    } catch (IllegalStateException expected) {
      assertThat(only().getError()).isSameAs(expected);
      return;
    }
    throw new AssertionError("the summariser's failure was swallowed");
  }

  @Test
  void a_summariser_is_observed_once() {
    Summarizer once = ObservedSummarizer.wrap(chapter -> "text", registry);

    assertThat(ObservedSummarizer.wrap(once, registry)).isSameAs(once);
  }

  @Test
  void a_summariser_is_wrapped_even_when_the_registry_has_no_handlers_yet() {
    Summarizer plain = chapter -> "text";

    assertThat(ObservedSummarizer.wrap(plain, ObservationRegistry.NOOP)).isNotSameAs(plain);
  }

  @Test
  void a_policy_question_is_a_span_that_says_how_many_turns_it_was_shown_and_how_many_it_closed() {
    ChapterPolicy observed = ObservedChapterPolicy.wrap(ChapterPolicy.every(2), registry);
    OpenTurns open = new OpenTurns(TYPE, AGENT, List.of(new TurnId(1), new TurnId(3)));

    List<TurnId> ends = observed.ends(open);

    assertThat(ends).containsExactly(new TurnId(3));
    Observation.Context span = only();
    assertThat(span.getName()).isEqualTo("nessy.chapter.policy");
    assertThat(span.getLowCardinalityKeyValue(Identity.AGENT_NAME).getValue()).isEqualTo("chat");
    assertThat(span.getHighCardinalityKeyValue("nessy.chapter.open").getValue()).isEqualTo("2");
    assertThat(span.getHighCardinalityKeyValue("nessy.chapter.closed").getValue()).isEqualTo("1");
  }

  @Test
  void a_policy_is_observed_once() {
    ChapterPolicy once = ObservedChapterPolicy.wrap(ChapterPolicy.every(2), registry);

    assertThat(ObservedChapterPolicy.wrap(once, registry)).isSameAs(once);
  }

  @Test
  void a_policy_is_wrapped_even_when_the_registry_has_no_handlers_yet() {
    ChapterPolicy plain = ChapterPolicy.every(2);

    assertThat(ObservedChapterPolicy.wrap(plain, ObservationRegistry.NOOP)).isNotSameAs(plain);
  }
}
