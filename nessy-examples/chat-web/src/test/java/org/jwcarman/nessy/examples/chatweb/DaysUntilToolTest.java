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
package org.jwcarman.nessy.examples.chatweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;

class DaysUntilToolTest {

  private final DaysUntilTool tool = new DaysUntilTool();

  private static ToolResult said(String text) {
    return ToolResult.ok(new Block.Text(text));
  }

  private static ToolCallRequest<DaysUntilTool.Input> asking(String date) {
    return new ToolCallRequest<>() {
      @Override
      public AgentType agentType() {
        return ChatConfiguration.TYPE;
      }

      @Override
      public AgentId agentId() {
        return new AgentId(UUID.randomUUID());
      }

      @Override
      public TurnId turn() {
        return new TurnId(1);
      }

      @Override
      public CallId callId() {
        return new CallId("c1");
      }

      @Override
      public ToolName toolName() {
        return new ToolName("days_until");
      }

      @Override
      public DaysUntilTool.Input input() {
        return new DaysUntilTool.Input(date);
      }

      @Override
      public Instant deadline() {
        return Instant.now().plusSeconds(30);
      }

      @Override
      public ReplyToken replyToken() {
        return new ReplyToken("unused");
      }
    };
  }

  @Test
  void countsForwardToADateThisTurnOfTheCentury() {
    Awaited<ToolResult> answer = tool.call(asking(LocalDate.now().plusDays(3).toString()));
    assertThat(answer).isEqualTo(Awaited.ready(said("3 days")));
  }

  @Test
  void countsBackwardsForADateAlreadyPast() {
    Awaited<ToolResult> answer = tool.call(asking(LocalDate.now().minusDays(2).toString()));
    assertThat(answer).isEqualTo(Awaited.ready(said("-2 days")));
  }

  @Test
  void tellsTheModelWhenTheDateIsNotADate() {
    Awaited<ToolResult> answer = tool.call(asking("next Tuesday"));
    assertThat(answer)
        .isInstanceOfSatisfying(
            Awaited.Ready.class,
            ready ->
                assertThat(ready.value())
                    .isInstanceOfSatisfying(
                        ToolResult.Failure.class,
                        failure ->
                            assertThat(failure.message())
                                .contains("next Tuesday")
                                .contains("ISO-8601")));
  }
}
