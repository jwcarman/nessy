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

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("The watchman's tools")
class WatchmanToolsTest {

  private final CommandRunner runner = new FakeRunner();

  @Nested
  @DisplayName("Reporting what it found")
  class ReportingWhatItFound {

    @Test
    void disk_usage_reports_one_line_per_filesystem() {
      String report = WatchmanTools.run(runner, "disk_usage", "{}");

      assertThat(report).isEqualTo("/ 91% used, 9G free");
    }

    @Test
    void containers_flags_the_ones_that_need_attention() {
      String report = WatchmanTools.run(runner, "containers", "{}");

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
      assertThat(WatchmanTools.action("prune_images", "{}")).isEqualTo("docker image prune -af");
    }
  }

  @Nested
  @DisplayName("The schemas the model is given")
  class TheSchemasTheModelIsGiven {

    @Test
    void every_tool_is_offered_with_a_description() {
      var specs = WatchmanTools.specs();
      List<String> names = specs.stream().map(org.jwcarman.nessy.api.tool.ToolSpec::name).toList();

      assertThat(names).isNotEmpty();
      assertThat(names)
          .containsExactlyInAnyOrder("disk_usage", "containers", "prune_images", "long_job");
      specs.forEach(spec -> assertThat(spec.description()).isNotBlank());
    }
  }

  @Nested
  @DisplayName("A tool the host cannot run")
  class AToolTheHostCannotRun {

    @Test
    void an_unknown_tool_answers_the_model_instead_of_throwing() {
      String report = WatchmanTools.run(runner, "reboot_everything", "{}");

      assertThat(report).isEqualTo("no such tool: reboot_everything");
    }

    @Test
    void a_command_that_fails_becomes_a_message_the_model_can_read() {
      CommandRunner broken =
          (argv, timeout) -> new CommandRunner.Output(1, "", "docker: no such host");

      String report = WatchmanTools.run(broken, "containers", "{}");

      assertThat(report).isEqualTo("docker failed: docker: no such host");
    }
  }

  @Test
  void dwell_is_reported_in_the_coarsest_unit_that_is_still_true() {
    assertThat(PendingApprovals.dwell(Duration.ofMinutes(20))).isEqualTo("20m");
    assertThat(PendingApprovals.dwell(Duration.ofHours(5).plusMinutes(3))).isEqualTo("5h 3m");
    assertThat(PendingApprovals.dwell(Duration.ofDays(2).plusHours(7))).isEqualTo("2d 7h");
  }
}
