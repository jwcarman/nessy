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
package org.jwcarman.nessy.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("What a call cost")
class UsageTest {

  @Nested
  @DisplayName("an unreported count")
  class Unreported {

    @Test
    @DisplayName("is not the same fact as zero")
    void is_not_zero() {
      Usage silent = new Usage(606, 142);
      Usage measured = new Usage(606, 142, 0, 0);

      assertThat(silent.cacheReadInputTokens()).isNull();
      assertThat(measured.cacheReadInputTokens()).isZero();
      assertThat(silent).isNotEqualTo(measured);
    }

    /** The shape LM Studio actually sends: prompt and completion tokens, no cache detail at all. */
    @Test
    void is_what_a_provider_that_keeps_no_cache_books_reports() {
      Usage usage = new Usage(606, 142);

      assertThat(usage.inputTokens()).isEqualTo(606);
      assertThat(usage.outputTokens()).isEqualTo(142);
      assertThat(usage.cacheReadInputTokens()).isNull();
      assertThat(usage.cacheWriteInputTokens()).isNull();
    }

    @Test
    @DisplayName("covers every count when the provider said nothing at all")
    void is_every_count_when_the_stream_never_reported() {
      Usage nothing = Usage.unreported();

      assertThat(nothing.inputTokens()).isNull();
      assertThat(nothing.outputTokens()).isNull();
      assertThat(nothing.cacheReadInputTokens()).isNull();
      assertThat(nothing.cacheWriteInputTokens()).isNull();
    }

    @Test
    @DisplayName("makes a total impossible rather than zero")
    void has_no_total() {
      assertThat(Usage.unreported().totalTokens()).isNull();
      assertThat(new Usage(606, 142).totalTokens()).isEqualTo(748);
    }
  }

  @Nested
  @DisplayName("the subset rule")
  class SubsetRule {

    @Test
    @DisplayName("catches an adapter that summed when it should have passed through")
    void refuses_cache_counts_larger_than_the_input_they_are_part_of() {
      assertThatThrownBy(() -> new Usage(10, 5, 8, 4))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot exceed inputTokens");
    }

    @Test
    void allows_cache_counts_that_exactly_fill_the_input() {
      Usage everythingCached = new Usage(10, 5, 6, 4);

      assertThat(everythingCached.cacheReadInputTokens()).isEqualTo(6);
    }

    /**
     * A partial report is ordinary — an input total with no cache detail is the common case — so
     * absence must not be judged against a rule it says nothing about.
     */
    @Test
    @DisplayName("says nothing about counts that were never reported")
    void is_not_applied_to_absent_counts() {
      Usage partial = new Usage(10, 5, null, null);

      assertThat(partial.inputTokens()).isEqualTo(10);
    }
  }

  @Test
  void refuses_a_negative_count_naming_the_one_that_was_negative() {
    assertThatThrownBy(() -> new Usage(10, 5, -1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cacheReadInputTokens");
  }
}
