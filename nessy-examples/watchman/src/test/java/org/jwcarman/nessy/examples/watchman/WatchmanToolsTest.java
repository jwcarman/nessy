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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;

@DisplayName("The watchman's tools")
class WatchmanToolsTest {

  private static final JsonNode NO_ARGUMENTS = JsonNodeFactory.instance.objectNode();

  /** What the engine tells a running tool. These tools read none of it. */
  private static final ToolCallRequest<JsonNode> NOWHERE =
      new ToolCallRequest<>(
          AgentType.of("watchman"),
          AgentId.of("house"),
          "turn-1",
          "c1",
          "a_tool",
          NO_ARGUMENTS,
          new ReplyToken("nowhere"));

  private final CommandRunner runner = new FakeRunner();

  private static Map<String, Tool<JsonNode>> toolsOf(CommandRunner runner) {
    return WatchmanTools.boundTo(runner).stream()
        .collect(java.util.stream.Collectors.toMap(Tool::name, Function.identity()));
  }

  private static String textOf(Awaited<ToolResult> awaited) {
    ToolResult result = ((Awaited.Ready<ToolResult>) awaited).result();
    return switch (result) {
      case ToolResult.Success success ->
          success.content().stream()
              .map(block -> ((TextBlock) block).text())
              .collect(java.util.stream.Collectors.joining("\n"));
      case ToolResult.Failure failure -> failure.message();
    };
  }

  private String run(CommandRunner runner, String tool) {
    return textOf(toolsOf(runner).get(tool).execute(NOWHERE));
  }

  @Nested
  @DisplayName("Reporting what it found")
  class ReportingWhatItFound {

    @Test
    void disk_usage_reports_one_line_per_filesystem() {
      assertThat(run(runner, "disk_usage")).isEqualTo("/ 91% used, 9G free");
    }

    @Test
    void containers_flags_the_ones_that_need_attention() {
      String report = run(runner, "containers");

      assertThat(report)
          .contains("grafana running")
          .doesNotContain("grafana running (Up 2 days) <--");
      assertThat(report).contains("loki exited").contains("needs attention");
    }
  }

  @Nested
  @DisplayName("Deciding who needs a human")
  class DecidingWhoNeedsAHuman {

    @Test
    void only_prune_images_needs_approval() {
      assertThat(WatchmanTools.needsApproval("prune_images")).isTrue();
      assertThat(WatchmanTools.needsApproval("disk_usage")).isFalse();
      assertThat(WatchmanTools.needsApproval("containers")).isFalse();
      assertThat(WatchmanTools.needsApproval("long_job")).isFalse();
    }

    @Test
    void the_action_a_human_is_shown_is_the_line_that_will_run() {
      assertThat(WatchmanTools.describe("prune_images", NO_ARGUMENTS))
          .isEqualTo("docker image prune -af");
    }
  }

  @Nested
  @DisplayName("What the model is offered")
  class WhatTheModelIsOffered {

    @Test
    void every_tool_is_offered_with_a_description_and_a_schema() {
      List<Tool<JsonNode>> tools = WatchmanTools.boundTo(runner);

      assertThat(tools).isNotEmpty();
      assertThat(tools)
          .extracting(Tool::name)
          .containsExactlyInAnyOrder("disk_usage", "containers", "prune_images", "long_job");
      tools.forEach(
          tool -> {
            assertThat(tool.description()).isNotBlank();
            // Every watchman tool takes no arguments, and says so rather than saying nothing.
            assertThat(tool.inputSchema().get("type").asText()).isEqualTo("object");
          });
    }
  }

  @Nested
  @DisplayName("A tool the host cannot run")
  class AToolTheHostCannotRun {

    @Test
    void a_command_that_fails_becomes_a_failure_the_model_can_read() {
      CommandRunner broken =
          (argv, timeout) -> new CommandRunner.Output(1, "", "docker: no such host");

      Awaited<ToolResult> answer = toolsOf(broken).get("containers").execute(NOWHERE);

      // A failed command is a Failure now, not a success carrying an error string — the model is
      // told plainly that nothing happened.
      assertThat(((Awaited.Ready<ToolResult>) answer).result())
          .isInstanceOf(ToolResult.Failure.class);
      assertThat(textOf(answer)).isEqualTo("docker failed: docker: no such host");
    }
  }
}
