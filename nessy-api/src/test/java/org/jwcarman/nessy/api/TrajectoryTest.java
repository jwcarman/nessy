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
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TrajectoryTest {

  private static final String HASH = "a".repeat(64);

  @Test
  void a_trajectory_is_a_version_and_sixty_four_lowercase_hex_characters() {
    Trajectory trajectory = new Trajectory((short) 1, HASH);
    assertThat(trajectory.version()).isEqualTo((short) 1);
    assertThat(trajectory.hash()).isEqualTo(HASH);
  }

  @Test
  void a_hash_that_is_not_sixty_four_lowercase_hex_characters_is_refused() {
    assertThatThrownBy(() -> new Trajectory((short) 1, "A".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_hash_of_the_wrong_length_is_refused() {
    assertThatThrownBy(() -> new Trajectory((short) 1, "ab"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void every_turn_outcome_has_a_distinct_tag() {
    assertThat(TurnOutcome.values()).extracting(TurnOutcome::tag).doesNotHaveDuplicates();
    assertThat(TurnOutcome.ANSWERED.tag()).isEqualTo((byte) 1);
    assertThat(TurnOutcome.STOPPED.tag()).isEqualTo((byte) 5);
  }
}
