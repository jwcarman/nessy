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
package org.jwcarman.nessy.spi.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;

class TokenEstimatorTest {

  @Test
  void four_characters_make_a_token() {
    Message message = Message.user("a".repeat(400));

    long estimate = TokenEstimator.heuristic().estimate(message);

    assertThat(estimate).isEqualTo(100);
  }

  @Test
  void every_message_costs_at_least_one_token() {
    Message empty = Message.user("");

    long estimate = TokenEstimator.heuristic().estimate(empty);

    assertThat(estimate).isEqualTo(1);
  }

  @Test
  void tool_results_count_their_content() {
    Message message =
        Message.toolResults(List.of(new ToolResultBlock("call-1", "a".repeat(40), false)));

    long estimate = TokenEstimator.heuristic().estimate(message);

    assertThat(estimate).isEqualTo(10);
  }

  @Test
  void text_and_tool_result_content_both_count_toward_the_same_message() {
    Message message =
        Message.user(
            List.of(
                new TextBlock("a".repeat(20)),
                new ToolResultBlock("call-1", "b".repeat(20), false)));

    long estimate = TokenEstimator.heuristic().estimate(message);

    assertThat(estimate).isEqualTo(10);
  }
}
