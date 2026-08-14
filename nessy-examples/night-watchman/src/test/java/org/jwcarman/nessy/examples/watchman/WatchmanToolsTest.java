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
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The watchman's two hands, pinned without Spring: the wire names the model calls, and both execute
 * paths returning {@code Ready} — no parks anywhere in this example (spec §7).
 */
class WatchmanToolsTest {

  private static ToolContext context(String callId, String name) {
    ToolCall call = new ToolCall(callId, name, JsonNodeFactory.instance.objectNode());
    return new ToolContext(ConversationId.generate(), call, EventEmitter.noop());
  }

  @Test
  void check_vitals_reads_all_three_gauges() {
    CheckVitalsTool tool = new CheckVitalsTool(new EngineRoom(42L));
    assertThat(tool.name()).isEqualTo("check_vitals");

    Awaited<ToolResult> awaited =
        tool.execute(new CheckVitalsTool.Input(), context("c1", "check_vitals"));

    assertThat(awaited).isInstanceOf(Awaited.Ready.class);
    ToolResult result = ((Awaited.Ready<ToolResult>) awaited).value();
    assertThat(result.isError()).isFalse();
    assertThat(result.content()).contains("boiler pressure");
    assertThat(result.content()).contains("bilge level");
    assertThat(result.content()).contains("hull stress");
  }

  @Test
  void raise_alarm_answers_ready_and_echoes_the_cause() {
    RaiseAlarmTool tool = new RaiseAlarmTool();
    assertThat(tool.name()).isEqualTo("raise_alarm");

    Awaited<ToolResult> awaited =
        tool.execute(
            new RaiseAlarmTool.Input("high", "bilge level climbing three rounds straight"),
            context("c2", "raise_alarm"));

    assertThat(awaited).isInstanceOf(Awaited.Ready.class);
    ToolResult result = ((Awaited.Ready<ToolResult>) awaited).value();
    assertThat(result.isError()).isFalse();
    assertThat(result.content()).contains("high");
    assertThat(result.content()).contains("bilge level climbing three rounds straight");
  }
}
