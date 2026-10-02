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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ChapterPolicyTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId ID = new AgentId(UUID.randomUUID());

  private static OpenTurns open(long... ids) {
    List<TurnId> turns = new ArrayList<>();
    for (long id : ids) {
      turns.add(new TurnId(id));
    }
    return new OpenTurns(TYPE, ID, turns);
  }

  @Nested
  class Every {

    @Test
    void closes_nothing_while_fewer_turns_are_open() {
      assertThat(ChapterPolicy.every(3).ends(open(5, 9))).isEmpty();
    }

    @Test
    void closes_the_oldest_turns_once_enough_are_open() {
      assertThat(ChapterPolicy.every(3).ends(open(5, 9, 12))).containsExactly(new TurnId(12));
    }

    @Test
    void leaves_newer_turns_open() {
      assertThat(ChapterPolicy.every(3).ends(open(5, 9, 12, 20, 31)))
          .containsExactly(new TurnId(12));
    }

    @Test
    void refuses_a_chapter_of_no_turns() {
      assertThatThrownBy(() -> ChapterPolicy.every(0)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class Open_turns {

    @Test
    void the_list_is_copied() {
      List<TurnId> turns = new ArrayList<>(List.of(new TurnId(5)));
      OpenTurns open = new OpenTurns(TYPE, ID, turns);
      turns.add(new TurnId(9));
      assertThat(open.turns()).containsExactly(new TurnId(5));
    }

    @Test
    void a_null_agent_type_is_refused() {
      List<TurnId> turns = List.of();
      assertThatThrownBy(() -> new OpenTurns(null, ID, turns))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("agentType");
    }

    @Test
    void a_null_agent_id_is_refused() {
      List<TurnId> turns = List.of();
      assertThatThrownBy(() -> new OpenTurns(TYPE, null, turns))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("agentId");
    }

    @Test
    void a_null_list_of_turns_is_refused() {
      assertThatThrownBy(() -> new OpenTurns(TYPE, ID, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("turns");
    }
  }
}
