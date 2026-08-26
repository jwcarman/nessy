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

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.nessy.api.tool.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Every read-only tool, against a host that never existed (spec §4).
 *
 * <p>What each of these asserts is the same two things: the command it asked for, argument for
 * argument, and what it made of the answer. Those are the parts that can be wrong. Whether {@code
 * ProcessBuilder} can start a process is the JDK's business.
 */
class ReadOnlyToolsTest {

  @Nested
  class Disk_usage {

    private static final String DF =
        """
        Filesystem      Size  Used Avail Use% Mounted on
        /dev/sda1       234G  198G   24G  90% /
        tmpfs           7.8G  1.2M  7.8G   1% /dev/shm
        """;

    @Test
    void asks_df_for_posix_output() {
      FakeRunner runner = new FakeRunner().answering("df", DF);

      Tools.content(DiskUsage.tool(runner), new DiskUsage.Mounts());

      assertThat(runner.onlyAsked()).containsExactly("df", "-hP");
    }

    @Test
    void reports_a_percentage_and_the_free_space_per_mount() {
      FakeRunner runner = new FakeRunner().answering("df", DF);

      String report = Tools.content(DiskUsage.tool(runner), new DiskUsage.Mounts());

      assertThat(report).contains("/ 90% used, 24G free").contains("/dev/shm 1% used, 7.8G free");
      assertThat(report).doesNotContain("Filesystem");
    }

    @Test
    void says_so_when_df_fails() {
      FakeRunner runner =
          new FakeRunner().answering("df", new CommandRunner.Output(1, "", "df: bad option"));

      String report = Tools.content(DiskUsage.tool(runner), new DiskUsage.Mounts());

      assertThat(report).isEqualTo("df failed: df: bad option");
    }
  }

  @Nested
  class Failed_units {

    @Test
    void asks_systemctl_for_the_failed_units_without_decoration() {
      FakeRunner runner = new FakeRunner().answering("systemctl", "");

      Tools.content(FailedUnits.tool(runner), new FailedUnits.Failures());

      assertThat(runner.onlyAsked())
          .containsExactly("systemctl", "--failed", "--no-legend", "--plain", "--no-pager");
    }

    @Test
    void lists_the_failed_units() {
      FakeRunner runner =
          new FakeRunner()
              .answering(
                  "systemctl",
                  """
                  nginx.service loaded failed failed A high performance web server
                  backup.timer  loaded failed failed Nightly backup
                  """);

      String report = Tools.content(FailedUnits.tool(runner), new FailedUnits.Failures());

      assertThat(report).contains("nginx.service").contains("backup.timer");
    }

    @Test
    void says_no_failed_units_in_words_rather_than_saying_nothing() {
      FakeRunner runner = new FakeRunner().answering("systemctl", "\n");

      String report = Tools.content(FailedUnits.tool(runner), new FailedUnits.Failures());

      assertThat(report).isEqualTo("no failed units");
    }
  }

  @Nested
  class Journal_errors {

    @Test
    void asks_journalctl_for_the_last_thirty_minutes_by_default() {
      FakeRunner runner = new FakeRunner().answering("journalctl", "");

      Tools.content(JournalErrors.tool(runner), new JournalErrors.Window(null));

      assertThat(runner.onlyAsked()).containsSequence("--since", "-30m");
    }

    @Test
    void honours_a_window_the_model_asked_for() {
      FakeRunner runner = new FakeRunner().answering("journalctl", "");

      Tools.content(JournalErrors.tool(runner), new JournalErrors.Window(180));

      assertThat(runner.onlyAsked()).containsSequence("--since", "-180m");
    }

    @Test
    void truncates_a_flood_rather_than_handing_the_model_ten_thousand_lines() {
      String flood = "kernel: I/O error\n".repeat(400);
      FakeRunner runner = new FakeRunner().answering("journalctl", flood);

      String report = Tools.content(JournalErrors.tool(runner), new JournalErrors.Window(null));

      assertThat(report.lines().count()).isLessThan(120);
      assertThat(report).contains("300 more error lines");
    }

    @Test
    void says_the_window_it_found_nothing_in() {
      FakeRunner runner = new FakeRunner().answering("journalctl", "");

      String report = Tools.content(JournalErrors.tool(runner), new JournalErrors.Window(15));

      assertThat(report).isEqualTo("no errors in the last 15 minutes");
    }
  }

  @Nested
  class Containers_ {

    private static final String PS =
        """
        {"Names":"grafana","State":"running","Status":"Up 3 days"}
        {"Names":"backup","State":"exited","Status":"Exited (1) 2 hours ago"}
        {"Names":"api","State":"running","Status":"Up 1 day (unhealthy)"}
        """;

    @Test
    void asks_docker_for_every_container_as_json() {
      FakeRunner runner = new FakeRunner().answering("docker", PS);

      Tools.content(Containers.tool(runner), new Containers.Inventory());

      assertThat(runner.onlyAsked()).containsExactly("docker", "ps", "-a", "--format", "json");
    }

    @Test
    void flags_the_exited_and_the_unhealthy_and_leaves_the_healthy_alone() {
      FakeRunner runner = new FakeRunner().answering("docker", PS);

      String report = Tools.content(Containers.tool(runner), new Containers.Inventory());

      assertThat(report).contains("backup exited (Exited (1) 2 hours ago) <-- needs attention");
      assertThat(report).contains("api running (Up 1 day (unhealthy)) <-- needs attention");
      assertThat(report).contains("grafana running (Up 3 days)");
      assertThat(report.lines().filter(line -> line.startsWith("grafana")).toList())
          .singleElement()
          .satisfies(line -> assertThat(line).doesNotContain("needs attention"));
    }

    @Test
    void survives_a_line_that_is_not_json() {
      FakeRunner runner = new FakeRunner().answering("docker", "not json at all\n");

      String report = Tools.content(Containers.tool(runner), new Containers.Inventory());

      assertThat(report).contains("unparseable docker output");
    }
  }

  @Nested
  class Updates_pending {

    @Test
    void asks_apt_on_a_debian_box() {
      FakeRunner runner = new FakeRunner().answering("apt", "Listing...\nnginx/stable 1.2 amd64\n");

      String report =
          Tools.content(
              UpdatesPending.tool(runner, PackageManager.APT), new UpdatesPending.Upgradable());

      assertThat(runner.onlyAsked()).containsExactly("apt", "list", "--upgradable");
      assertThat(report).isEqualTo("nginx/stable 1.2 amd64");
    }

    @Test
    void reads_dnfs_exit_code_100_as_updates_rather_than_as_a_failure() {
      FakeRunner runner =
          new FakeRunner()
              .answering("dnf", new CommandRunner.Output(100, "kernel.x86_64 6.9.4\n", ""));

      String report =
          Tools.content(
              UpdatesPending.tool(runner, PackageManager.DNF), new UpdatesPending.Upgradable());

      assertThat(report).isEqualTo("kernel.x86_64 6.9.4");
    }

    @Test
    void says_no_updates_pending_when_there_are_none() {
      FakeRunner runner = new FakeRunner().answering("apt", "Listing...\n");

      String report =
          Tools.content(
              UpdatesPending.tool(runner, PackageManager.APT), new UpdatesPending.Upgradable());

      assertThat(report).isEqualTo("no updates pending");
    }
  }

  @Nested
  class Uptime_load {

    @Test
    void reads_the_two_proc_files_and_says_load_and_uptime(@TempDir Path proc) throws IOException {
      Files.writeString(proc.resolve("loadavg"), "0.52 0.31 0.28 1/512 90210\n");
      Files.writeString(proc.resolve("uptime"), "864000.12 3400000.00\n");
      UptimeLoad uptime = new UptimeLoad(proc);

      String report = Tools.content(uptime.tool(), new UptimeLoad.Health());

      assertThat(uptime.available()).isTrue();
      assertThat(report).contains("load 0.52 0.31 0.28").contains("up 10d 0h 0m");
    }

    @Test
    void is_unavailable_where_there_is_no_proc(@TempDir Path empty) {
      assertThat(new UptimeLoad(empty).available()).isFalse();
    }
  }

  /**
   * The drift that feature detection cannot survive: a tool whose argv no longer starts with the
   * command its bean is gated on.
   *
   * <p>Nothing else catches it. Change {@code journal_errors} to shell out to {@code logger} and
   * every other test here still passes — the tool works against a fake runner, the bean is still
   * created, and the only symptom is on a real host, where a box with no {@code logger} advertises
   * a tool that fails every call, or a box with {@code logger} and no {@code journalctl} hides a
   * tool that would have worked. So each tool is RUN, and the command it actually asked for is
   * compared against the {@code watchman.detected.*} property {@link ToolBeans} gates it on — read
   * off the annotation, not restated here.
   *
   * <p>This replaces an earlier test that asserted {@code Detect.COMMANDS} equalled a local copy of
   * the same literal. That is a change-detector: it can only fail when someone edits both halves
   * inconsistently, and it never once looked at what a tool runs.
   */
  @Nested
  class Every_tools_argv_matches_the_gate_its_bean_is_registered_under {

    /**
     * The command named by {@code @ConditionalOnProperty} on {@link ToolBeans}'s method of this
     * name — i.e. what feature detection has to find for this bean to exist at all.
     */
    private static String gateOf(String beanMethod) {
      Method method =
          Arrays.stream(ToolBeans.class.getDeclaredMethods())
              .filter(candidate -> candidate.getName().equals(beanMethod))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no ToolBeans method named " + beanMethod));
      ConditionalOnProperty gate = method.getAnnotation(ConditionalOnProperty.class);
      assertThat(gate)
          .describedAs("%s is expected to be gated on a detected command", beanMethod)
          .isNotNull();
      assertThat(gate.name()).hasSize(1);
      assertThat(gate.name()[0]).startsWith(Detect.PREFIX);
      return gate.name()[0].substring(Detect.PREFIX.length());
    }

    private static void assertAgrees(String beanMethod, Function<CommandRunner, Tool<?>> factory) {
      FakeRunner runner = new FakeRunner();
      Tool<?> tool = factory.apply(runner);
      String gate = gateOf(beanMethod);

      Tools.runWithPlaceholderInput(tool);
      String asked = runner.onlyAsked().getFirst();

      assertThat(asked)
          .describedAs("%s runs '%s' but its bean is gated on '%s'", tool.name(), asked, gate)
          .isEqualTo(gate);
      assertThat(Detect.COMMANDS)
          .describedAs("'%s' must be something Detect actually looks for", gate)
          .contains(gate);
    }

    @Test
    void disk_usage() {
      assertAgrees("diskUsage", runner -> DiskUsage.tool(runner));
    }

    @Test
    void failed_units() {
      assertAgrees("failedUnits", runner -> FailedUnits.tool(runner));
    }

    @Test
    void journal_errors() {
      assertAgrees("journalErrors", runner -> JournalErrors.tool(runner));
    }

    @Test
    void containers() {
      assertAgrees("containers", runner -> Containers.tool(runner));
    }

    @Test
    void restart_unit() {
      assertAgrees("restartUnit", runner -> RestartUnit.tool(runner));
    }

    @Test
    void restart_container() {
      assertAgrees("restartContainer", runner -> RestartContainer.tool(runner));
    }

    @Test
    void prune_images() {
      assertAgrees("pruneImages", runner -> PruneImages.tool(runner));
    }

    @Test
    void clean_journal() {
      assertAgrees("cleanJournal", runner -> CleanJournal.tool(runner));
    }

    /**
     * {@code updates_pending} and {@code apply_updates} are the two that cannot use the check
     * above, because they are gated on an expression rather than one property. What matters for
     * them is the same thing stated the other way: whichever package manager was detected, that is
     * the command the tool runs.
     */
    @Test
    void the_package_manager_pair_runs_whichever_manager_was_detected() {
      for (PackageManager manager : PackageManager.values()) {
        FakeRunner checking = new FakeRunner();
        FakeRunner upgrading = new FakeRunner();

        Tools.content(UpdatesPending.tool(checking, manager), new UpdatesPending.Upgradable());
        Tools.content(
            ApplyUpdates.tool(upgrading, manager, Duration.ofMinutes(15)),
            new ApplyUpdates.Updates());

        assertThat(checking.onlyAsked().getFirst())
            .isEqualTo(manager.name().toLowerCase(Locale.ROOT));
        assertThat(Detect.COMMANDS).contains(manager.name().toLowerCase(Locale.ROOT));
        // apt's upgrade command is apt-GET, so the gate is the check command's first word, not the
        // upgrade command's — assert the relationship rather than an equality that is not true.
        assertThat(upgrading.onlyAsked().getFirst())
            .startsWith(manager.name().toLowerCase(Locale.ROOT));
      }
    }

    /** {@code long_job} defers instead of returning, so it is run through its own door. */
    @Test
    void long_job() {
      FakeRunner runner = new FakeRunner();
      String gate = gateOf("longJob");
      Tool<LongJob.Job> tool = LongJob.tool(runner, (id, result) -> {}, Runnable::run);

      tool.execute(new LongJob.Job(), new FakeContext());

      assertThat(runner.onlyAsked().getFirst()).isEqualTo(gate);
      assertThat(Detect.COMMANDS).contains(gate);
    }
  }
}
