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
package org.jwcarman.nessy.api.compaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.session.SessionId;
import org.jwcarman.nessy.api.session.SessionState;

class CompactionTriggerTest {

  private static SessionState stateWithLastInputTokens(long lastInputTokens) {
    return SessionState.newSession(new SessionId("s1")).withLastInputTokens(lastInputTokens);
  }

  @Nested
  class At_tokens {

    @Test
    void at_tokens_fires_at_and_above_the_threshold() {
      CompactionTrigger trigger = CompactionTrigger.atTokens(100_000);

      assertThat(trigger.shouldCompact(stateWithLastInputTokens(99_999))).isFalse();
      assertThat(trigger.shouldCompact(stateWithLastInputTokens(100_000))).isTrue();
      assertThat(trigger.shouldCompact(stateWithLastInputTokens(100_001))).isTrue();
    }

    @Test
    void a_trigger_below_one_is_rejected() {
      assertThatThrownBy(() -> CompactionTrigger.atTokens(0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class For_window {

    @Test
    void for_window_reserves_the_reply_room() {
      // 0.8 * (200_000 - 8_192) = 153_446.4, truncated to 153_446 by the (long) cast.
      CompactionTrigger trigger = CompactionTrigger.forWindow(200_000, 8_192);

      assertThat(trigger.shouldCompact(stateWithLastInputTokens(153_445))).isFalse();
      assertThat(trigger.shouldCompact(stateWithLastInputTokens(153_446))).isTrue();
    }

    @Test
    void for_window_rejects_a_window_smaller_than_max_tokens() {
      assertThatThrownBy(() -> CompactionTrigger.forWindow(1_000, 2_000))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void for_window_rejects_a_window_equal_to_max_tokens() {
      assertThatThrownBy(() -> CompactionTrigger.forWindow(2_000, 2_000))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class Never {

    @Test
    void never_never_fires() {
      CompactionTrigger trigger = CompactionTrigger.never();

      assertThat(trigger.shouldCompact(stateWithLastInputTokens(0))).isFalse();
      assertThat(trigger.shouldCompact(stateWithLastInputTokens(Long.MAX_VALUE))).isFalse();
    }

    /**
     * A stable singleton, not a fresh lambda per call: {@code CompactionPolicy.disabled()} depends
     * on this identity so {@code AgentBuilder} can recognize a never-compacting policy by {@code
     * equals}, rather than by a heuristic.
     */
    @Test
    void never_is_the_same_instance_every_call() {
      assertThat(CompactionTrigger.never()).isSameAs(CompactionTrigger.never());
    }
  }
}
