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
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TokenEstimator;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

class MemoriesTest {

  private static ToolCall call(String id) {
    return new ToolCall(id, "read_file", JsonNodeFactory.instance.objectNode());
  }

  private static Message toolUse(String callId) {
    return Message.assistant(List.of(new ToolUseBlock(call(callId))));
  }

  @Nested
  class Recall {

    @Test
    void a_recent_tool_result_survives_elision_while_an_older_one_is_replaced() {
      // The trap named in review: elideToolResults(0) would elide EVERY tool result, including
      // the one the model is about to be called again to interpret — that is a worse failure
      // than being over budget. Budgeted.recall() must instead elide only tool results OLDER
      // than the round in flight (everything since the last genuine human turn).
      //
      // Shape: [start] [old tool_use/tool_result exchange] [next] [recent tool_use/tool_result
      // exchange]. The old exchange's result is a 600-character "dump" (150 tokens under the
      // heuristic estimator: characters / 4); so is the recent one. Budget 250 sits below the
      // unelided total (302) but above what eliding just the old result leaves (154), so the
      // fallback message-dropping in limitTokens never triggers — this isolates elision alone.
      Substrate substrate = new InMemorySubstrate(Clock.systemUTC());
      Memories memories = new Memories(substrate, 250);
      Memory memory = memories.forAgent("agent-a");
      String oldDump = "OLD".repeat(200);
      String recentDump = "NEW".repeat(200);

      memory.remember(new Remembrance.UserMessage("turn-0", Message.user("start")));
      memory.remember(new Remembrance.AssistantMessage("turn-1", toolUse("old-call")));
      memory.remember(
          new Remembrance.ToolExchange("exec-old", call("old-call"), ToolResult.ok(oldDump)));
      memory.remember(new Remembrance.UserMessage("turn-2", Message.user("next")));
      memory.remember(new Remembrance.AssistantMessage("turn-3", toolUse("new-call")));
      memory.remember(
          new Remembrance.ToolExchange("exec-new", call("new-call"), ToolResult.ok(recentDump)));

      Context recalled = memory.recall();

      assertThat(recalled.messages()).hasSize(6);
      assertThat(toolResultContent(recalled, "old-call")).isEqualTo("[elided]");
      assertThat(toolResultContent(recalled, "new-call")).isEqualTo(recentDump);
      assertThat(recalled.tokens(TokenEstimator.heuristic())).isLessThanOrEqualTo(250);
    }
  }

  private static String toolResultContent(Context context, String toolUseId) {
    for (Message message : context.messages()) {
      for (ContentBlock block : message.content()) {
        if (block instanceof ToolResultBlock(String id, String content, boolean _)
            && id.equals(toolUseId)) {
          return content;
        }
      }
    }
    throw new AssertionError("no tool_result for " + toolUseId);
  }
}
