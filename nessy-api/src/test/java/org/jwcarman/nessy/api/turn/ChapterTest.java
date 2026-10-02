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
package org.jwcarman.nessy.api.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ChapterTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId ID = new AgentId(UUID.randomUUID());

  @Nested
  class Runs_forwards {

    @Test
    void a_chapter_whose_first_turn_is_after_its_last_is_refused() {
      TurnId from = new TurnId(9);
      TurnId through = new TurnId(5);
      assertThatThrownBy(() -> new Chapter(TYPE, ID, from, through))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must run forwards");
    }

    @Test
    void a_chapter_of_one_turn_is_accepted() {
      Chapter chapter = new Chapter(TYPE, ID, new TurnId(5), new TurnId(5));
      assertThat(chapter.from()).isEqualTo(chapter.through());
    }
  }

  @Nested
  class Rejects_nulls {

    @Test
    void a_null_agent_type_is_refused() {
      TurnId from = new TurnId(1);
      TurnId through = new TurnId(2);
      assertThatThrownBy(() -> new Chapter(null, ID, from, through))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("agentType");
    }

    @Test
    void a_null_agent_id_is_refused() {
      TurnId from = new TurnId(1);
      TurnId through = new TurnId(2);
      assertThatThrownBy(() -> new Chapter(TYPE, null, from, through))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("agentId");
    }

    @Test
    void a_null_from_is_refused() {
      TurnId through = new TurnId(2);
      assertThatThrownBy(() -> new Chapter(TYPE, ID, null, through))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("from");
    }

    @Test
    void a_null_through_is_refused() {
      TurnId from = new TurnId(1);
      assertThatThrownBy(() -> new Chapter(TYPE, ID, from, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("through");
    }
  }

  @Nested
  class Covers {

    private final Chapter chapter = new Chapter(TYPE, ID, new TurnId(5), new TurnId(12));

    @Test
    void its_first_turn_is_covered() {
      assertThat(chapter.covers(new TurnId(5))).isTrue();
    }

    @Test
    void its_last_turn_is_covered() {
      assertThat(chapter.covers(new TurnId(12))).isTrue();
    }

    @Test
    void a_turn_between_is_covered() {
      assertThat(chapter.covers(new TurnId(9))).isTrue();
    }

    @Test
    void a_turn_before_the_first_is_not_covered() {
      assertThat(chapter.covers(new TurnId(3))).isFalse();
    }

    @Test
    void a_turn_after_the_last_is_not_covered() {
      assertThat(chapter.covers(new TurnId(13))).isFalse();
    }
  }
}
