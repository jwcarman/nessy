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

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Summary;

class TurnIdAndSummaryTest {

  @Test
  void a_turn_id_is_a_story_position_and_orders_like_one() {
    assertThatThrownBy(() -> new TurnId(0)).isInstanceOf(IllegalArgumentException.class);
    assertThat(new TurnId(3).openedAt()).isEqualTo(new Seq(3));
    assertThat(new TurnId(3)).isLessThan(new TurnId(5));
    assertThat(new Seq(7).opensTurn()).isEqualTo(new TurnId(7));
  }

  @Test
  void a_summary_runs_forwards_and_says_something() {
    TurnId five = new TurnId(5);
    TurnId three = new TurnId(3);
    TurnId one = new TurnId(1);
    List<Block.SummaryContent> nothing = List.of();
    assertThatThrownBy(() -> Summary.text(five, three, "backwards"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forwards");
    assertThatThrownBy(() -> new Summary(one, three, nothing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("say something");

    Summary summary = Summary.text(new TurnId(1), new TurnId(9), "lakes");
    assertThat(summary.covers(new TurnId(9))).isTrue();
    assertThat(summary.covers(new TurnId(11))).isFalse();
  }
}
