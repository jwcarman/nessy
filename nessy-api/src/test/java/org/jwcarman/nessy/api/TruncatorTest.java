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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TruncatorTest {

  private static final String TEXT = "abcdefghij";

  private static void assertNoLoneSurrogate(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        assertThat(i + 1).isLessThan(text.length());
        assertThat(Character.isLowSurrogate(text.charAt(i + 1))).isTrue();
        i++;
      } else {
        assertThat(Character.isLowSurrogate(c)).isFalse();
      }
    }
  }

  @Nested
  class Drop_tail {

    private final Truncator truncator = Truncator.dropTail();

    @Test
    void text_within_the_limit_comes_back_as_given() {
      assertThat(truncator.truncate("abc", 10)).isEqualTo("abc");
      assertThat(truncator.truncate(TEXT, 10)).isEqualTo(TEXT);
    }

    @Test
    void text_over_the_limit_comes_back_at_exactly_the_limit() {
      assertThat(truncator.truncate(TEXT, 8)).hasSize(8);
    }

    @Test
    void keeps_the_start_and_puts_the_marker_at_the_end() {
      assertThat(truncator.truncate(TEXT, 8)).isEqualTo("abcde...");
    }

    @Test
    void a_character_of_two_code_units_is_kept_or_dropped_whole() {
      String text = "abc😀defgh";
      for (int limit = 1; limit < 9; limit++) {
        String cut = truncator.truncate(text, limit);
        assertNoLoneSurrogate(cut);
        assertThat(cut.codePointCount(0, cut.length())).isLessThanOrEqualTo(limit);
      }
      assertThat(truncator.truncate(text, 6)).isEqualTo("abc...");
      assertThat(truncator.truncate(text, 7)).isEqualTo("abc😀...");
    }

    @Test
    void a_limit_counts_characters_as_a_reader_does() {
      String text = "😀😀😀😀😀😀";
      assertThat(truncator.truncate(text, 6)).isEqualTo(text);
      String cut = truncator.truncate(text, 5);
      assertThat(cut.codePointCount(0, cut.length())).isEqualTo(5);
    }

    @Test
    void a_limit_too_small_for_the_marker_is_a_plain_cut() {
      assertThat(truncator.truncate(TEXT, 3)).isEqualTo("abc");
      assertThat(truncator.truncate(TEXT, 1)).isEqualTo("a");
    }

    @Test
    void a_limit_of_four_leaves_room_for_the_marker_and_one_character() {
      assertThat(truncator.truncate(TEXT, 4)).isEqualTo("a...");
    }

    @Test
    void a_limit_below_one_is_refused() {
      assertThatThrownBy(() -> truncator.truncate(TEXT, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("limit");
    }

    @Test
    void null_text_is_refused() {
      assertThatThrownBy(() -> truncator.truncate(null, 5))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class Drop_head {

    private final Truncator truncator = Truncator.dropHead();

    @Test
    void text_within_the_limit_comes_back_as_given() {
      assertThat(truncator.truncate("abc", 10)).isEqualTo("abc");
      assertThat(truncator.truncate(TEXT, 10)).isEqualTo(TEXT);
    }

    @Test
    void text_over_the_limit_comes_back_at_exactly_the_limit() {
      assertThat(truncator.truncate(TEXT, 8)).hasSize(8);
    }

    @Test
    void keeps_the_end_and_puts_the_marker_at_the_start() {
      assertThat(truncator.truncate(TEXT, 8)).isEqualTo("...fghij");
    }

    @Test
    void a_character_of_two_code_units_is_kept_or_dropped_whole() {
      String text = "abc😀defgh";
      for (int limit = 1; limit < 9; limit++) {
        String cut = truncator.truncate(text, limit);
        assertNoLoneSurrogate(cut);
        assertThat(cut.codePointCount(0, cut.length())).isLessThanOrEqualTo(limit);
      }
      assertThat(truncator.truncate(text, 8)).isEqualTo("...defgh");
      assertThat(truncator.truncate(text, 9)).isEqualTo(text);
    }

    @Test
    void a_limit_too_small_for_the_marker_is_a_plain_cut_keeping_the_end() {
      assertThat(truncator.truncate(TEXT, 3)).isEqualTo("hij");
      assertThat(truncator.truncate(TEXT, 1)).isEqualTo("j");
    }

    @Test
    void a_limit_of_four_leaves_room_for_the_marker_and_one_character() {
      assertThat(truncator.truncate(TEXT, 4)).isEqualTo("...j");
    }

    @Test
    void a_limit_below_one_is_refused() {
      assertThatThrownBy(() -> truncator.truncate(TEXT, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("limit");
    }

    @Test
    void null_text_is_refused() {
      assertThatThrownBy(() -> truncator.truncate(null, 5))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class Drop_middle {

    private final Truncator truncator = Truncator.dropMiddle();

    @Test
    void text_within_the_limit_comes_back_as_given() {
      assertThat(truncator.truncate("abc", 10)).isEqualTo("abc");
      assertThat(truncator.truncate(TEXT, 10)).isEqualTo(TEXT);
    }

    @Test
    void text_over_the_limit_comes_back_at_exactly_the_limit() {
      assertThat(truncator.truncate(TEXT, 8)).hasSize(8);
    }

    @Test
    void keeps_both_ends_and_the_odd_character_goes_to_the_start() {
      assertThat(truncator.truncate(TEXT, 8)).isEqualTo("abc...ij");
    }

    @Test
    void an_even_share_is_split_evenly() {
      assertThat(truncator.truncate(TEXT, 9)).isEqualTo("abc...hij");
    }

    @Test
    void a_character_of_two_code_units_is_kept_or_dropped_whole() {
      String text = "abc😀defgh";
      for (int limit = 1; limit < 9; limit++) {
        String cut = truncator.truncate(text, limit);
        assertNoLoneSurrogate(cut);
        assertThat(cut.codePointCount(0, cut.length())).isLessThanOrEqualTo(limit);
      }
      assertThat(truncator.truncate(text, 8)).isEqualTo("abc...gh");
      assertThat(truncator.truncate(text, 9)).isEqualTo(text);
    }

    @Test
    void a_limit_too_small_for_the_marker_is_a_plain_cut() {
      assertThat(truncator.truncate(TEXT, 3)).isEqualTo("abc");
      assertThat(truncator.truncate(TEXT, 4)).isEqualTo("abcd");
    }

    @Test
    void a_limit_of_five_leaves_room_for_the_marker_and_a_character_each_side() {
      assertThat(truncator.truncate(TEXT, 5)).isEqualTo("a...j");
    }

    @Test
    void a_limit_below_one_is_refused() {
      assertThatThrownBy(() -> truncator.truncate(TEXT, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("limit");
    }

    @Test
    void null_text_is_refused() {
      assertThatThrownBy(() -> truncator.truncate(null, 5))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class The_three_supplied {

    @Test
    void are_shared_instances() {
      assertThat(Truncator.dropTail()).isSameAs(Truncator.dropTail());
      assertThat(Truncator.dropHead()).isSameAs(Truncator.dropHead());
      assertThat(Truncator.dropMiddle()).isSameAs(Truncator.dropMiddle());
    }
  }
}
