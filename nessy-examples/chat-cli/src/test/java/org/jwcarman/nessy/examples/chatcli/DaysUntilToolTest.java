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
package org.jwcarman.nessy.examples.chatcli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ToolResult;

class DaysUntilToolTest {

  private final DaysUntilTool tool = new DaysUntilTool();

  @Test
  void countsForwardToADateThisTurnOfTheCentury() {
    Awaited<ToolResult> answer =
        tool.execute(
            new DaysUntilTool.Input(java.time.LocalDate.now().plusDays(3).toString()), null);

    assertThat(answer)
        .isInstanceOfSatisfying(
            Awaited.Ready.class,
            ready -> assertThat(ready.result()).isEqualTo(ToolResult.ok("3 days")));
  }

  @Test
  void countsBackwardsForADateAlreadyPast() {
    Awaited<ToolResult> answer =
        tool.execute(
            new DaysUntilTool.Input(java.time.LocalDate.now().minusDays(2).toString()), null);

    assertThat(answer)
        .isInstanceOfSatisfying(
            Awaited.Ready.class,
            ready -> assertThat(ready.result()).isEqualTo(ToolResult.ok("-2 days")));
  }

  /**
   * A model that supplies nonsense gets an answer it can read and retry from, not an exception that
   * fails the call — which is the whole reason {@link ToolResult} has a failed arm.
   */
  @Test
  void tellsTheModelWhenTheDateIsNotADate() {
    Awaited<ToolResult> answer = tool.execute(new DaysUntilTool.Input("next Tuesday"), null);

    assertThat(answer)
        .isInstanceOfSatisfying(
            Awaited.Ready.class,
            ready ->
                assertThat(ready.result())
                    .isInstanceOfSatisfying(
                        ToolResult.Failure.class,
                        failure ->
                            assertThat(failure.message())
                                .contains("next Tuesday")
                                .contains("ISO-8601")));
  }
}
