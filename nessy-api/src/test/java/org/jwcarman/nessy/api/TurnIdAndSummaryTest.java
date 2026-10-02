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

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Summary;

class TurnIdAndSummaryTest {

  @Test
  void a_turn_id_is_a_story_position_and_orders_like_one() {
    assertThatThrownBy(() -> new TurnId(0)).isInstanceOf(IllegalArgumentException.class);
    assertThat(new TurnId(3).openedAt()).isEqualTo(new Seq(3));
    assertThat(new TurnId(3)).isLessThan(new TurnId(5));
    assertThat(new Seq(7).opensTurn()).isEqualTo(new TurnId(7));
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class A_summary {

    private final Chapter chapter =
        new Chapter(new AgentType("chat"), AgentId.random(), new TurnId(1), new TurnId(9));

    @Test
    void keeps_its_chapter_and_its_text() {
      Summary summary = new Summary(chapter, "lakes");

      assertThat(summary.chapter()).isEqualTo(chapter);
      assertThat(summary.text()).isEqualTo("lakes");
    }

    @Test
    void must_say_something() {
      assertThatThrownBy(() -> new Summary(chapter, "  "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("say something");
    }

    @Test
    void must_have_a_chapter() {
      assertThatThrownBy(() -> new Summary(null, "lakes"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("chapter");
    }

    @Test
    void must_have_text() {
      assertThatThrownBy(() -> new Summary(chapter, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("text");
    }

    @Test
    void covers_the_turns_its_chapter_covers() {
      Summary summary = new Summary(chapter, "lakes");

      assertThat(summary.covers(new TurnId(1))).isTrue();
      assertThat(summary.covers(new TurnId(9))).isTrue();
      assertThat(summary.covers(new TurnId(11))).isFalse();
    }
  }
}
