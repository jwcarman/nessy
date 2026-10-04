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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StoryProjectionTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId AGENT = AgentId.random();
  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

  private static Narrated at(long seq, Narration.Story event) {
    return Narrated.story(TYPE, AGENT, event, new Seq(seq), AT);
  }

  @Test
  void a_projection_made_with_of_counts_the_turns_it_is_given() {
    StoryProjection<Integer> turns =
        StoryProjection.of(
            0, (n, narrated) -> narrated.event() instanceof Narration.TurnStarted ? n + 1 : n);
    List<Narrated> story =
        List.of(
            at(1, new Narration.TurnStarted(new TurnId(1), "Question", Instant.EPOCH)),
            at(2, new Narration.Terminated()),
            at(3, new Narration.TurnStarted(new TurnId(3), "Question", Instant.EPOCH)));

    int counted = turns.initial();
    for (Narrated narrated : story) {
      counted = turns.apply(counted, narrated);
    }

    assertThat(counted).isEqualTo(2);
  }

  @Test
  void a_projection_made_with_of_starts_from_the_value_it_was_given() {
    StoryProjection<String> projection = StoryProjection.of("start", (soFar, narrated) -> soFar);

    assertThat(projection.initial()).isEqualTo("start");
  }

  @Test
  void a_projection_made_with_of_may_start_from_nothing() {
    StoryProjection<String> projection = StoryProjection.of(null, (soFar, narrated) -> soFar);

    assertThat(projection.initial()).isNull();
  }

  @Test
  void a_projection_made_without_a_step_is_refused() {
    BiFunction<String, Narrated, String> none = null;

    assertThatThrownBy(() -> StoryProjection.of("start", none))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("step must not be null");
  }
}
