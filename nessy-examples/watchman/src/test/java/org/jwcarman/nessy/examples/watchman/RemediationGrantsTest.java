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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CompletionPolicy;
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

  /** Whatever watchman.upgrade-timeout is set to; these tests only care that it is honoured. */
  private static final Duration UPGRADE_TIMEOUT = Duration.ofMinutes(15);

  /** One backslash, spelled once, so the quote-escaping assertion stays readable. */
  private static final String BACKSLASH = "\\";

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

  /** The whole of an approval context now (deferral-by-callback spec §7). */
  private record ParkingContext(ApprovalRequest request) implements ApprovalContext {}

  @Nested
  class Restart_unit {

    private final ToolGrant grant = RestartUnit.grant(RUNNER);

    @Test
    void shows_the_exact_systemctl_line() {
      assertThat(action(grant, new RestartUnit.Unit("nginx.service")))
          .isEqualTo("systemctl restart -- nginx.service");
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
          .isEqualTo("docker restart -- grafana");
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
      assertThat(
              action(
                  ApplyUpdates.grant(RUNNER, PackageManager.APT, UPGRADE_TIMEOUT),
                  new ApplyUpdates.Updates()))
          .isEqualTo("apt-get -y upgrade");
      assertThat(
              action(
                  ApplyUpdates.grant(RUNNER, PackageManager.DNF, UPGRADE_TIMEOUT),
                  new ApplyUpdates.Updates()))
          .isEqualTo("dnf -y upgrade");
    }

    @Test
    void waits_for_a_human() {
      assertThat(
              outcome(
                  ApplyUpdates.grant(RUNNER, PackageManager.APT, UPGRADE_TIMEOUT),
                  new ApplyUpdates.Updates()))
          .isInstanceOf(ApprovalOutcome.Deferred.class);
    }

    /**
     * The one remediation that must NOT get the default deadline (final review, finding #4). {@code
     * watchman.command-timeout} is thirty seconds and the runner enforces a timeout by destroying
     * the process, so the default budget on {@code apt-get -y upgrade} is a SIGKILL to dpkg
     * mid-transaction — a package database a human then repairs by hand, on the server this agent
     * was supposed to be looking after.
     */
    @Test
    void asks_for_its_own_much_longer_deadline_rather_than_the_default() {
      FakeRunner runner = new FakeRunner().answering("apt-get", "");

      Tools.content(
          ApplyUpdates.tool(runner, PackageManager.APT, UPGRADE_TIMEOUT),
          new ApplyUpdates.Updates());

      assertThat(runner.timeouts()).containsExactly(UPGRADE_TIMEOUT);
      assertThat(UPGRADE_TIMEOUT).isGreaterThan(Duration.ofMinutes(5));
    }

    /** Every other remediation is a second's work and takes the runner's default. */
    @Test
    void the_quick_remediations_do_not_override_the_default_deadline() {
      FakeRunner runner = new FakeRunner().answering("systemctl", "");

      Tools.content(RestartUnit.tool(runner), new RestartUnit.Unit("nginx.service"));

      assertThat(runner.timeouts()).isEmpty();
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

  /**
   * The name in a remediation call comes from the MODEL, and the rendered line is the whole
   * question a human answers two days later. So the two ways that rendering can lie are worth
   * pinning: a name with a space that reads as two arguments, and a name starting with a dash that
   * reads as a flag.
   */
  @Nested
  class A_hostile_name {

    @Test
    void with_a_space_is_quoted_so_it_cannot_read_as_two_units() {
      String rendered = action(RestartUnit.grant(RUNNER), new RestartUnit.Unit("web api"));

      assertThat(rendered).isEqualTo("systemctl restart -- 'web api'");
      // The bug this replaces: "systemctl restart web api" reads as two units on the page and
      // executes as one unit whose name contains a space.
      assertThat(rendered).doesNotContain("restart web api");
    }

    @Test
    void starting_with_a_dash_is_fenced_off_by_the_end_of_options_marker() {
      RestartUnit.Unit flagLike = new RestartUnit.Unit("--version");

      assertThat(action(RestartUnit.grant(RUNNER), flagLike))
          .isEqualTo("systemctl restart -- --version");
      assertThat(RestartUnit.argv(flagLike))
          .containsExactly("systemctl", "restart", "--", "--version");
    }

    @Test
    void containing_a_quote_is_escaped_rather_than_closing_the_quoting() {
      String rendered =
          action(RestartContainer.grant(RUNNER), new RestartContainer.Container("it's here"));

      assertThat(rendered).isEqualTo("docker restart -- 'it'" + BACKSLASH + "''s here'");
    }

    /**
     * A model can omit a required field, and then the record holds {@code null}. Nothing renders,
     * no command runs, and — the part worth pinning — the failure names the stage rather than
     * surfacing as an anonymous NPE somewhere downstream. {@code ToolGrant} fails closed at the
     * action stage, which is exactly where a call this malformed should die.
     */
    @Test
    void that_is_missing_entirely_fails_closed_at_the_action_stage() {
      ToolGrant grant = RestartUnit.grant(RUNNER);
      RestartUnit.Unit nameless = new RestartUnit.Unit(null);

      assertThatThrownBy(() -> action(grant, nameless))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("action stage");
    }

    @Test
    void with_a_separator_in_it_is_quoted_even_though_no_shell_is_involved() {
      RestartContainer.Container tricky = new RestartContainer.Container("db; other");

      assertThat(action(RestartContainer.grant(RUNNER), tricky))
          .isEqualTo("docker restart -- 'db; other'");
      // Nothing here goes near a shell. The point is that the page must never SHOW something whose
      // apparent meaning differs from the argv's.
      assertThat(RestartContainer.argv(tricky))
          .containsExactly("docker", "restart", "--", "db; other");
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
          .contains("systemctl restart -- nginx.service")
          .contains("exit 5")
          .contains("not found");
    }
  }
}
