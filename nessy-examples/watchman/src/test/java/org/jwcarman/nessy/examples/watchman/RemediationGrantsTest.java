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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.approval.ApprovalContext;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * The five remediation grants (spec §2.1). Two things are asserted of each, and they are the two
 * that a human's safety depends on:
 *
 * <ol>
 *   <li>the rendered action is <b>the literal command line</b> that will run — because that string
 *       is the whole question the approval page asks; and
 *   <li>the approver defers rather than deciding — because nothing on this list may happen without
 *       a person.
 * </ol>
 */
class RemediationGrantsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final CommandRunner RUNNER = new FakeRunner();

  /** The action a grant would show on the page for {@code input}. */
  private static String action(ToolGrant grant, Object input) {
    ToolCall call = new ToolCall("c1", grant.tool().name(), JsonNodeFactory.instance.objectNode());
    ApprovalRequest request = grant.request("watchman", "watchman", call, input, MAPPER);
    return request.action();
  }

  /** What a grant's approver does when asked. */
  private static ApprovalOutcome outcome(ToolGrant grant, Object input) {
    ToolCall call = new ToolCall("c1", grant.tool().name(), JsonNodeFactory.instance.objectNode());
    ApprovalRequest request = grant.request("watchman", "watchman", call, input, MAPPER);
    ApprovalContext context = new ParkingContext(request);
    return grant.approver().approve(context);
  }

  /** An approval context whose {@code defer()} is the only door this test needs. */
  private record ParkingContext(ApprovalRequest request) implements ApprovalContext {
    @Override
    public ApprovalOutcome defer() {
      return new ApprovalOutcome.Deferred(ComputationId.of("parked"));
    }
  }

  @Nested
  class Restart_unit {

    private final ToolGrant grant = RestartUnit.grant(RUNNER);

    @Test
    void shows_the_exact_systemctl_line() {
      assertThat(action(grant, new RestartUnit.Unit("nginx.service")))
          .isEqualTo("systemctl restart nginx.service");
    }

    @Test
    void waits_for_a_human() {
      assertThat(outcome(grant, new RestartUnit.Unit("nginx.service")))
          .isInstanceOf(ApprovalOutcome.Deferred.class);
    }

    @Test
    void expects_its_approval_to_outlive_the_process_that_asked() {
      assertThat(grant.tool().requiredCompletion()).isEqualTo(CompletionPolicy.DURABLE);
    }
  }

  @Nested
  class Restart_container {

    private final ToolGrant grant = RestartContainer.grant(RUNNER);

    @Test
    void shows_the_exact_docker_line() {
      assertThat(action(grant, new RestartContainer.Container("grafana")))
          .isEqualTo("docker restart grafana");
    }

    @Test
    void waits_for_a_human() {
      assertThat(outcome(grant, new RestartContainer.Container("grafana")))
          .isInstanceOf(ApprovalOutcome.Deferred.class);
    }
  }

  @Nested
  class Prune_images {

    private final ToolGrant grant = PruneImages.grant(RUNNER);

    @Test
    void shows_the_flag_that_makes_it_dangerous() {
      assertThat(action(grant, new PruneImages.Prune())).isEqualTo("docker image prune -af");
    }

    @Test
    void waits_for_a_human() {
      assertThat(outcome(grant, new PruneImages.Prune()))
          .isInstanceOf(ApprovalOutcome.Deferred.class);
    }
  }

  @Nested
  class Apply_updates {

    @Test
    void shows_the_hosts_own_upgrade_command() {
      assertThat(action(ApplyUpdates.grant(RUNNER, PackageManager.APT), new ApplyUpdates.Updates()))
          .isEqualTo("apt-get -y upgrade");
      assertThat(action(ApplyUpdates.grant(RUNNER, PackageManager.DNF), new ApplyUpdates.Updates()))
          .isEqualTo("dnf -y upgrade");
    }

    @Test
    void waits_for_a_human() {
      assertThat(
              outcome(ApplyUpdates.grant(RUNNER, PackageManager.APT), new ApplyUpdates.Updates()))
          .isInstanceOf(ApprovalOutcome.Deferred.class);
    }
  }

  @Nested
  class Clean_journal {

    private final ToolGrant grant = CleanJournal.grant(RUNNER);

    @Test
    void puts_the_retention_in_the_line_so_two_asks_read_differently() {
      assertThat(action(grant, new CleanJournal.Retention(2)))
          .isEqualTo("journalctl --vacuum-time=2d");
      assertThat(action(grant, new CleanJournal.Retention(30)))
          .isEqualTo("journalctl --vacuum-time=30d");
    }

    @Test
    void reads_a_missing_retention_conservatively() {
      assertThat(action(grant, new CleanJournal.Retention(null)))
          .isEqualTo("journalctl --vacuum-time=7d");
    }

    @Test
    void waits_for_a_human() {
      assertThat(outcome(grant, new CleanJournal.Retention(7)))
          .isInstanceOf(ApprovalOutcome.Deferred.class);
    }
  }

  @Nested
  class Once_approved {

    @Test
    void the_tool_runs_the_very_line_the_page_showed() {
      FakeRunner runner = new FakeRunner().answering("systemctl", "");
      RestartUnit.Unit unit = new RestartUnit.Unit("nginx.service");
      String shown = action(RestartUnit.grant(runner), unit);

      Tools.content(RestartUnit.tool(runner), unit);

      assertThat(String.join(" ", runner.onlyAsked())).isEqualTo(shown);
    }

    @Test
    void a_failed_command_comes_back_as_a_message_the_model_can_read() {
      FakeRunner runner =
          new FakeRunner()
              .answering(
                  "systemctl", new CommandRunner.Output(5, "", "Unit nginx.service not found"));

      String answer =
          Tools.content(RestartUnit.tool(runner), new RestartUnit.Unit("nginx.service"));

      assertThat(answer)
          .contains("systemctl restart nginx.service")
          .contains("exit 5")
          .contains("not found");
    }
  }
}
