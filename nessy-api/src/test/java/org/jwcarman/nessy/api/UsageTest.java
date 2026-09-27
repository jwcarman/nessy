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

/** What a call cost, and the difference between a zero and a silence. */
@DisplayName("A usage")
class UsageTest {

  @Nested
  @DisplayName("Absence")
  class Absence {

    /**
     * <b>Null is not zero, and this is the assertion that keeps it that way.</b> A reply that cost
     * nothing and a reply nobody measured price differently and graph differently, and collapsing
     * them is the mistake this type exists to prevent.
     */
    @Test
    void distinguishes_a_counted_zero_from_nobody_counting() {
      assertThat(Usage.unreported().counted()).isFalse();
      assertThat(Usage.unreported().totalTokens().counted())
          .as("nobody counted, so the total is not a number")
          .isFalse();
      assertThat(Usage.of("a-model", 0, 0).counted()).isTrue();
      assertThat(Usage.of("a-model", 0, 0).totalTokens()).isEqualTo(Tokens.of(0));
    }

    /**
     * <b>Per field, not all-or-nothing.</b> Gemini leaves even the input count optional and an
     * OpenAI-compatible server reports input and output with no cache detail at all, so a usage
     * that could only be wholly known or wholly unknown could not describe what the vendors
     * actually say.
     */
    @Test
    void is_per_count_rather_than_all_or_nothing() {
      Usage partly = Usage.of("a-model", 25, 63);

      assertThat(partly.inputTokens()).isEqualTo(Tokens.of(25));
      assertThat(partly.cacheReadTokens()).isEqualTo(Tokens.none());
      assertThat(partly.reasoningTokens()).isEqualTo(Tokens.none());
      assertThat(partly.counted()).isTrue();
    }

    /** One side counted is the most that can honestly be totalled. */
    @Test
    void totals_the_side_that_was_counted_when_only_one_was() {
      assertThat(Usage.of("a-model", 25, null).totalTokens()).isEqualTo(Tokens.of(25));
      assertThat(Usage.of("a-model", null, 63).totalTokens()).isEqualTo(Tokens.of(63));
    }
  }

  @Nested
  @DisplayName("The model")
  class TheModel {

    /**
     * <b>A count with no model is unpriceable, so it is refused.</b> This is the invariant that
     * earns the model its place in this type rather than beside it.
     */
    @Test
    void is_required_of_anything_that_reports_a_count() {
      assertThatThrownBy(() -> new Usage(null, 10, 20, null, null, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must name the model");
    }

    /** Absent only when there is nothing to price: a scripted result, an unreached vendor. */
    @Test
    void may_be_absent_only_when_nothing_was_counted() {
      assertThat(Usage.unreported().model()).isNull();
      assertThat(Usage.unreported("a-model").model()).isEqualTo("a-model");
      assertThat(Usage.unreported("a-model").counted()).isFalse();
    }

    @Test
    void refuses_a_blank_one() {
      assertThatThrownBy(() -> Usage.of("  ", 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("The breakdowns")
  class TheBreakdowns {

    /**
     * <b>Cache and reasoning are inside the two totals, not beside them.</b> Adding them would
     * double-count, and a reader who believed otherwise would over-report every cached turn.
     */
    @Test
    void are_parts_of_the_input_and_output_rather_than_additions_to_them() {
      Usage usage =
          Usage.of("a-model", 1000, 200).withCacheRead(900).withCacheWrite(50).withReasoning(150);

      assertThat(usage.totalTokens()).isEqualTo(Tokens.of(1200));
      assertThat(usage.cacheReadTokens()).isEqualTo(Tokens.of(900));
      assertThat(usage.cacheWriteTokens()).isEqualTo(Tokens.of(50));
      assertThat(usage.reasoningTokens()).isEqualTo(Tokens.of(150));
    }

    @Test
    void keep_the_model_and_the_counts_they_were_added_to() {
      Usage usage = Usage.of("a-model", 10, 20).withCacheRead(5);

      assertThat(usage.model()).isEqualTo("a-model");
      assertThat(usage.inputTokens()).isEqualTo(Tokens.of(10));
      assertThat(usage.outputTokens()).isEqualTo(Tokens.of(20));
    }

    @Test
    void refuse_a_negative_count() {
      assertThatThrownBy(() -> Usage.of("a-model", 3, -1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
