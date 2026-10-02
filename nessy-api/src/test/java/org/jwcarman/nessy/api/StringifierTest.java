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
import tools.jackson.databind.json.JsonMapper;

class StringifierTest {

  record Query(String q, int n) {}

  static final class Unwritable {
    public String getValue() {
      throw new IllegalStateException("no value to be had");
    }
  }

  private static final Truncator UNCHANGED = (text, limit) -> text;

  @Nested
  class By_to_string {

    @Test
    void is_string_value_of() {
      Stringifier<Object> stringifier = Stringifier.byToString();

      assertThat(stringifier.stringify(new Query("lake", 3))).isEqualTo("Query[q=lake, n=3]");
      assertThat(stringifier.stringify("plain")).isEqualTo("plain");
      assertThat(stringifier.stringify(null)).isEqualTo("null");
    }
  }

  @Nested
  class Json {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void writes_the_value_as_the_mapper_does() {
      Stringifier<Query> stringifier = Stringifier.json(mapper);

      assertThat(stringifier.stringify(new Query("lake", 3))).isEqualTo("{\"q\":\"lake\",\"n\":3}");
    }

    @Test
    void a_value_the_mapper_cannot_write_makes_it_throw() {
      Stringifier<Unwritable> stringifier = Stringifier.json(mapper);
      Unwritable value = new Unwritable();

      assertThatThrownBy(() -> stringifier.stringify(value)).isInstanceOf(RuntimeException.class);
    }
  }

  @Nested
  class Dropping {

    private final Stringifier<String> plain = value -> value;

    @Test
    void a_dropper_makes_the_text_one_line_before_it_cuts() {
      assertThat(plain.dropTail(100).stringify("a\n\n  b\tc  ")).isEqualTo("a b c");
    }

    @Test
    void whitespace_that_is_not_ascii_counts() {
      assertThat(plain.dropTail(100).stringify("a\u2003\u2003b\u00a0c")).isEqualTo("a b c");
    }

    @Test
    void drop_tail_keeps_the_start() {
      assertThat(plain.dropTail(8).stringify("abcdefghij")).isEqualTo("abcde...");
    }

    @Test
    void drop_head_keeps_the_end() {
      assertThat(plain.dropHead(8).stringify("abcdefghij")).isEqualTo("...fghij");
    }

    @Test
    void drop_middle_keeps_both_ends() {
      assertThat(plain.dropMiddle(8).stringify("abcdefghij")).isEqualTo("abc...ij");
    }

    @Test
    void truncated_uses_the_truncator_given() {
      assertThat(plain.truncated((text, limit) -> "X", 8).stringify("abcdefghij")).isEqualTo("X");
    }

    @Test
    void a_limit_below_one_is_refused_when_the_wrapper_is_made() {
      assertThatThrownBy(() -> plain.dropTail(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("limit");
    }

    @Test
    void a_null_truncator_is_refused_when_the_wrapper_is_made() {
      assertThatThrownBy(() -> plain.truncated(null, 5)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_truncator_that_returns_more_than_the_limit_is_cut_to_it() {
      assertThat(plain.truncated(UNCHANGED, 4).stringify("abcdefghij")).isEqualTo("abcd");
    }

    @Test
    void the_guard_against_a_truncator_that_returns_too_much_cuts_between_whole_characters() {
      String cut = plain.truncated(UNCHANGED, 3).stringify("ab😀cd");

      assertThat(cut).isEqualTo("ab😀");
      assertThat(cut.codePointCount(0, cut.length())).isEqualTo(3);
      assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
    }

    @Test
    void a_truncator_that_returns_null_gives_an_empty_line() {
      assertThat(plain.truncated((text, limit) -> null, 5).stringify("abcdefghij")).isEmpty();
    }

    @Test
    void a_null_from_the_wrapped_stringifier_is_an_empty_line() {
      Stringifier<String> nothing = value -> null;

      assertThat(nothing.dropTail(10).stringify("anything")).isEmpty();
    }
  }

  @Nested
  class Wrapping_a_wrapper {

    private final Stringifier<String> plain = value -> value;

    @Test
    void asked_for_a_limit_at_or_above_its_own_returns_itself() {
      Stringifier<String> middle = plain.dropMiddle(200);

      assertThat(middle.dropTail(1000)).isSameAs(middle);
      assertThat(middle.dropTail(200)).isSameAs(middle);
      assertThat(middle.dropHead(200)).isSameAs(middle);
      assertThat(middle.dropMiddle(200)).isSameAs(middle);
      assertThat(middle.truncated(UNCHANGED, 500)).isSameAs(middle);
    }

    @Test
    void asked_for_a_smaller_limit_cuts_again() {
      Stringifier<String> middle = plain.dropMiddle(200);

      Stringifier<String> smaller = middle.dropTail(50);

      assertThat(smaller).isNotSameAs(middle);
      String text = "x".repeat(500);
      assertThat(smaller.stringify(text)).hasSize(50);
      assertThat(smaller.stringify(text)).endsWith("...");
    }

    @Test
    void asked_for_a_smaller_limit_applies_both_cuts() {
      Stringifier<String> head = plain.dropHead(10);

      Stringifier<String> both = head.dropTail(8);

      assertThat(head.stringify("abcdefghijklmnopqrstuvwxyz")).isEqualTo("...tuvwxyz");
      assertThat(both.stringify("abcdefghijklmnopqrstuvwxyz")).isEqualTo("...tu...");
    }

    @Test
    void a_null_truncator_is_refused_by_a_wrapper_too() {
      Stringifier<String> middle = plain.dropMiddle(200);

      assertThatThrownBy(() -> middle.truncated(null, 500))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_plain_stringifier_asked_to_drop_is_wrapped() {
      assertThat(plain.dropTail(10)).isNotSameAs(plain);
    }
  }
}
