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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** One count of tokens, or the absence of one -- and the difference between them. */
@DisplayName("Tokens")
class TokensTest {

  @Test
  @DisplayName("nobody saying is not the same fact as somebody saying zero")
  void absent_is_not_zero() {
    assertThat(Tokens.none()).isNotEqualTo(Tokens.of(0));
    assertThat(Tokens.none().counted()).isFalse();
    assertThat(Tokens.of(0).counted())
        .as("a vendor reporting no cache reads has told you something")
        .isTrue();
  }

  @Test
  @DisplayName("a vendor's absent box becomes an absent count, in one place")
  void a_null_box_becomes_uncounted() {
    assertThat(Tokens.reported(null)).isEqualTo(Tokens.none());
    assertThat(Tokens.reported(7)).isEqualTo(Tokens.of(7));
  }

  @Nested
  @DisplayName("adding")
  class Adding {

    @Test
    @DisplayName("a field nobody ever reported stays unreported rather than becoming zero")
    void absent_plus_absent_is_absent() {
      assertThat(Tokens.none().plus(Tokens.none())).isEqualTo(Tokens.none());
    }

    @Test
    @DisplayName("one quiet call does not make a total unknowable")
    void absent_plus_a_count_is_that_count() {
      assertThat(Tokens.none().plus(Tokens.of(10))).isEqualTo(Tokens.of(10));
      assertThat(Tokens.of(10).plus(Tokens.none())).isEqualTo(Tokens.of(10));
    }

    @Test
    @DisplayName("counts add")
    void counts_add() {
      assertThat(Tokens.of(10).plus(Tokens.of(4))).isEqualTo(Tokens.of(14));
    }

    @Test
    @DisplayName("a total of several calls reports what was reported and no more")
    void a_running_total() {
      Tokens total =
          Tokens.none()
              .plus(Tokens.none())
              .plus(Tokens.of(10))
              .plus(Tokens.none())
              .plus(Tokens.of(4));

      assertThat(total).isEqualTo(Tokens.of(14));
    }
  }

  @Nested
  @DisplayName("subtracting")
  class Subtracting {

    @Test
    @DisplayName("what the spending bought is the total less what was wasted")
    void a_difference() {
      assertThat(Tokens.of(100).minus(Tokens.of(30))).isEqualTo(Tokens.of(70));
    }

    @Test
    @DisplayName("nothing wasted leaves the total whole")
    void subtracting_absent_changes_nothing() {
      assertThat(Tokens.of(100).minus(Tokens.none())).isEqualTo(Tokens.of(100));
    }

    @Test
    @DisplayName("an unreported total stays unreported rather than turning into a number")
    void subtracting_from_absent_is_absent() {
      assertThat(Tokens.none().minus(Tokens.of(5))).isEqualTo(Tokens.none());
    }

    @Test
    @DisplayName("more wasted than spent reads as nothing left, never as less than nothing")
    void a_difference_never_goes_below_nothing() {
      assertThat(Tokens.of(10).minus(Tokens.of(40))).isEqualTo(Tokens.of(0));
    }
  }

  @Test
  @DisplayName("arithmetic can have a number, but only by asking for one")
  void or_zero_is_for_arithmetic() {
    assertThat(Tokens.none().orZero()).isZero();
    assertThat(Tokens.of(9).orZero()).isEqualTo(9);
  }

  @Test
  @DisplayName("a negative count is a bug where it is written, not where it is read")
  void negative_counts_are_refused() {
    assertThatThrownBy(() -> Tokens.of(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be negative");
  }
}
