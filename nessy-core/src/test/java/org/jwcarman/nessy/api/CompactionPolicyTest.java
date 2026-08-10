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

class CompactionPolicyTest {

  @Nested
  class Construction {

    @Test
    void a_valid_policy_retains_its_fields() {
      CompactionTrigger trigger = CompactionTrigger.atTokens(50_000);
      CompactionPolicy policy = new CompactionPolicy(trigger, 5, 1_024, "summarize");

      assertThat(policy.trigger()).isSameAs(trigger);
      assertThat(policy.keepRecentMessages()).isEqualTo(5);
      assertThat(policy.summaryMaxTokens()).isEqualTo(1_024);
      assertThat(policy.instructions()).isEqualTo("summarize");
    }

    @Test
    void a_null_trigger_is_rejected() {
      assertThatThrownBy(() -> new CompactionPolicy(null, 5, 1_024, "summarize"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void negative_keep_recent_messages_is_rejected() {
      assertThatThrownBy(
              () ->
                  new CompactionPolicy(CompactionTrigger.atTokens(50_000), -1, 1_024, "summarize"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_summary_ceiling_below_one_is_rejected() {
      assertThatThrownBy(
              () -> new CompactionPolicy(CompactionTrigger.atTokens(50_000), 5, 0, "summarize"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_instructions_are_rejected() {
      assertThatThrownBy(
              () -> new CompactionPolicy(CompactionTrigger.atTokens(50_000), 5, 1_024, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class Factories {

    @Test
    void defaults_trigger_at_one_hundred_thousand_tokens() {
      CompactionPolicy policy = CompactionPolicy.defaults();

      assertThat(policy.trigger().shouldCompact(stateWithLastInputTokens(99_999))).isFalse();
      assertThat(policy.trigger().shouldCompact(stateWithLastInputTokens(100_000))).isTrue();
      assertThat(policy.keepRecentMessages()).isEqualTo(10);
      assertThat(policy.summaryMaxTokens()).isEqualTo(2_048);
      assertThat(policy.instructions()).isEqualTo(CompactionPolicy.DEFAULT_INSTRUCTIONS);
    }

    @Test
    void disabled_never_triggers() {
      CompactionPolicy policy = CompactionPolicy.disabled();

      assertThat(policy.trigger().shouldCompact(stateWithLastInputTokens(Long.MAX_VALUE)))
          .isFalse();
      assertThat(policy.keepRecentMessages()).isEqualTo(10);
      assertThat(policy.summaryMaxTokens()).isEqualTo(2_048);
      assertThat(policy.instructions()).isEqualTo(CompactionPolicy.DEFAULT_INSTRUCTIONS);
    }
  }

  private static SessionState stateWithLastInputTokens(long lastInputTokens) {
    return SessionState.newSession(new SessionId("s1")).withLastInputTokens(lastInputTokens);
  }
}
