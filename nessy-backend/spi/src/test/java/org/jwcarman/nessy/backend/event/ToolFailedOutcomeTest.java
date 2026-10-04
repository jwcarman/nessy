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
package org.jwcarman.nessy.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolConfig;
import org.jwcarman.nessy.backend.effect.EffectOutcome;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ToolFailedOutcomeTest {

  private static final CallId CALL = new CallId("c1");

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class A_failed_calls_message {

    @Test
    void a_short_message_is_kept_as_it_was_given() {
      assertThat(
              new EffectOutcome.ToolFailed(CALL, CallFailure.FAILED, "the ledger is down")
                  .message())
          .isEqualTo("the ledger is down");
    }

    @Test
    void a_message_of_exactly_the_cap_is_kept_as_it_was_given() {
      String message = "m".repeat(ToolConfig.LINE_CAP);

      assertThat(new EffectOutcome.ToolFailed(CALL, CallFailure.FAILED, message).message())
          .isEqualTo(message);
    }

    @Test
    void a_long_message_keeps_its_start_and_its_end_and_drops_the_middle() {
      String message = "START" + "x".repeat(4_990) + "END";

      String kept = new EffectOutcome.ToolFailed(CALL, CallFailure.FAILED, message).message();

      assertThat(kept)
          .hasSize(ToolConfig.LINE_CAP)
          .startsWith("START")
          .endsWith("END")
          .contains("...");
    }

    @Test
    void line_breaks_in_a_message_are_left_alone() {
      String message = "first line\n  second line\n\nthird";

      assertThat(new EffectOutcome.ToolFailed(CALL, CallFailure.FAILED, message).message())
          .isEqualTo(message);
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class A_failure_without_its_parts {

    @Test
    void refuses_a_null_message() {
      assertThatThrownBy(() -> new EffectOutcome.ToolFailed(CALL, CallFailure.FAILED, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("message must not be null");
    }

    @Test
    void refuses_a_null_kind() {
      assertThatThrownBy(() -> new EffectOutcome.ToolFailed(CALL, null, "gone"))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("kind must not be null");
    }

    @Test
    void refuses_a_null_call_id() {
      assertThatThrownBy(() -> new EffectOutcome.ToolFailed(null, CallFailure.FAILED, "gone"))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("callId must not be null");
    }
  }
}
