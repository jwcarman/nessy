package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("The watchman's tools")
class WatchmanToolsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode NO_ARGUMENTS = JSON.createObjectNode();

  private static final ToolCallRequest<JsonNode> NOWHERE =
      new ToolCallRequest<>() {
        @Override
        public AgentType agentType() {
          return Watchman.TYPE;
        }

        @Override
        public AgentId agentId() {
          return Watchman.AGENT;
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
          return new ToolName("a_tool");
        }

        @Override
        public JsonNode input() {
          return NO_ARGUMENTS;
        }

        @Override
        public Instant deadline() {
          return Instant.EPOCH.plusSeconds(30);
        }

        @Override
        public ReplyToken replyToken() {
          return new ReplyToken("nowhere");
        }
      };

  private final CommandRunner runner = new FakeRunner();

  private static Map<String, Tool<JsonNode>> toolsOf(CommandRunner runner) {
    return WatchmanTools.boundTo(runner).stream()
        .collect(Collectors.toMap(tool -> tool.name().value(), Function.identity()));
  }

  private static String textOf(Awaited<ToolResult> awaited) {
    return switch (awaited) {
      case Awaited.Ready<ToolResult>(ToolResult.Success success) ->
          success.blocks().stream()
              .map(block -> ((Block.Text) block).text())
              .collect(Collectors.joining("\n"));
      case Awaited.Ready<ToolResult>(ToolResult.Failure failure) -> failure.message();
      case Awaited.Deferred<ToolResult> _ ->
          throw new AssertionError("a shell command is never deferred");
    };
  }

  private static String run(CommandRunner runner, String tool) {
    return textOf(toolsOf(runner).get(tool).call(NOWHERE));
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
          .doesNotContain("grafana running (Up 2 days) <--")
          .contains("loki exited")
          .contains("needs attention");
    }

    private static final String MAC_DF_H =
        """
        Filesystem        Size    Used   Avail Capacity iused ifree %iused  Mounted on
        /dev/disk3s1s1   926Gi    12Gi    37Gi    24%    459k  392M    0%   /
        devfs            203Ki   203Ki     0Bi   100%     702     0  100%   /dev
        /dev/disk3s6     926Gi    13Gi    37Gi    26%      13  392M    0%   /System/Volumes/VM
        /dev/disk3s5     926Gi   854Gi    37Gi    96%    7.5M  392M    2%   /System/Volumes/Data
        map auto_home      0Bi     0Bi     0Bi   100%       0     0     -   /System/Volumes/Data/home
        """;

    private static final String LINUX_DF_H =
        """
        Filesystem      Size  Used Avail Use% Mounted on
        /dev/sda1       100G   40G   60G  40% /
        tmpfs            16G   15G  1.0G  92% /run
        """;

    @Test
    void disk_usage_skips_devfs_and_the_autofs_placeholder_but_keeps_the_real_alarm() {
      String report = run(fixed("df -h", MAC_DF_H), "disk_usage");
      assertThat(report)
          .doesNotContain("devfs")
          .doesNotContain("/dev/")
          .doesNotContain("auto_home")
          .contains("/System/Volumes/Data 96% used, 37Gi free");
    }

    @Test
    void disk_usage_reports_human_readable_units_not_bare_block_counts() {
      assertThat(run(fixed("df -h", MAC_DF_H), "disk_usage")).contains("/ 24% used, 37Gi free");
    }

    @Test
    void disk_usage_does_not_blanket_skip_non_dev_filesystems_a_full_tmpfs_is_still_reported() {
      assertThat(run(fixed("df -h", LINUX_DF_H), "disk_usage"))
          .contains("/run 92% used, 1.0G free")
          .contains("/ 40% used, 60G free");
    }

    private static CommandRunner fixed(String expectedCommand, String stdout) {
      return (argv, timeout) -> {
        assertThat(String.join(" ", argv)).isEqualTo(expectedCommand);
        return new CommandRunner.Output(0, stdout, "");
      };
    }
  }

  @Nested
  @DisplayName("Deciding who needs a human")
  class DecidingWhoNeedsAHuman {

    @Test
    void only_prune_images_needs_approval() {
      assertThat(WatchmanTools.needsApproval(new ToolName("prune_images"))).isTrue();
      assertThat(WatchmanTools.needsApproval(new ToolName("disk_usage"))).isFalse();
      assertThat(WatchmanTools.needsApproval(new ToolName("containers"))).isFalse();
      assertThat(WatchmanTools.needsApproval(new ToolName("long_job"))).isFalse();
    }

    @Test
    void the_action_a_human_is_shown_is_the_line_that_will_run() {
      assertThat(WatchmanTools.actionOf(new ToolName("prune_images")))
          .isEqualTo("docker image prune -af");
    }
  }

  @Nested
  @DisplayName("What the model is offered")
  class WhatTheModelIsOffered {

    @Test
    void every_tool_is_offered_with_a_description_and_a_schema() {
      List<Tool<JsonNode>> tools = WatchmanTools.boundTo(runner);
      assertThat(tools)
          .extracting(tool -> tool.name().value())
          .containsExactlyInAnyOrder("disk_usage", "containers", "prune_images", "long_job");
      tools.forEach(
          tool -> {
            assertThat(tool.description()).isNotBlank();
            // Every watchman tool takes no arguments, and says so rather than saying nothing --
            // without asking a generator, which has nothing to say about a JsonNode.
            JsonNode schema =
                JSON.readTree(
                    tool.inputSchema(
                            type -> {
                              throw new AssertionError("the schema is the tool's own");
                            })
                        .json());
            assertThat(schema.path("type").asString()).isEqualTo("object");
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
      Awaited<ToolResult> answer = toolsOf(broken).get("containers").call(NOWHERE);
      // A failed command is a Failure, not a success carrying an error string: the model is told
      // plainly that nothing happened.
      assertThat(answer)
          .isInstanceOfSatisfying(
              Awaited.Ready.class,
              ready -> assertThat(ready.value()).isInstanceOf(ToolResult.Failure.class));
      assertThat(textOf(answer)).isEqualTo("docker failed: docker: no such host");
    }
  }
}
