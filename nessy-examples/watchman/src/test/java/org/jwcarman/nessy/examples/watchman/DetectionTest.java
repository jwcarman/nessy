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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Feature detection, and the thing it is for: an absent command means an absent tool (spec §2.1).
 *
 * <p>The second half is the one worth having. It is easy to write a detector and still hand the
 * model a {@code restart_container} tool on a host with no docker; what stops that is that the bean
 * is never created, and this asserts exactly that, on a pretend host, without running {@code which}
 * even once.
 */
class DetectionTest {

  @Nested
  class Which {

    @Test
    void asks_which_and_believes_the_exit_code() {
      FakeRunner runner =
          new FakeRunner()
              .answering("which", new CommandRunner.Output(0, "/usr/bin/df\n", ""))
              .otherwise(new CommandRunner.Output(1, "", ""));

      assertThat(new Detect(runner).present("df")).isTrue();
      assertThat(runner.onlyAsked()).containsExactly("which", "df");
    }

    @Test
    void reads_a_non_zero_exit_as_absent() {
      FakeRunner runner = new FakeRunner().otherwise(new CommandRunner.Output(1, "", ""));

      assertThat(new Detect(runner).present("docker")).isFalse();
    }

    @Test
    void publishes_both_answers_so_missing_never_means_undetected(@TempDir Path noProc) {
      FakeRunner runner = new FakeRunner().otherwise(new CommandRunner.Output(1, "", ""));

      Map<String, Object> properties = new Detect(runner, noProc).asProperties();

      assertThat(properties).isNotEmpty();
      assertThat(properties)
          .containsEntry("watchman.detected.docker", "false")
          .containsEntry("watchman.detected.df", "false")
          .containsEntry(Detect.PROC, "false");
      assertThat(properties).hasSize(Detect.COMMANDS.size() + 1);
    }
  }

  @Nested
  class The_tool_list_on_a_pretend_host {

    private final ApplicationContextRunner context =
        new ApplicationContextRunner()
            // TestRunner FIRST, on purpose: ToolBeans' own CommandRunner is
            // @ConditionalOnMissingBean, so the fake has to be registered before that condition is
            // evaluated. Otherwise this suite would quietly build a real ProcessRunner.
            .withUserConfiguration(TestHost.class, ToolBeans.class)
            .withPropertyValues(
                "watchman.user=ops",
                "watchman.password=lan-only",
                "watchman.notes-dir=target/detection-test-notes");

    private static List<String> toolNames(ApplicationContext applicationContext) {
      return applicationContext.getBeansOfType(Tool.class).values().stream()
          .map(tool -> ((Tool<?>) tool).name())
          .toList();
    }

    private static List<String> grantNames(ApplicationContext applicationContext) {
      return applicationContext.getBeansOfType(ToolGrant.class).values().stream()
          .map(grant -> grant.tool().name())
          .toList();
    }

    @Test
    void a_bare_box_gets_only_the_tools_that_need_nothing() {
      context.run(
          built -> {
            assertThat(toolNames(built)).isNotEmpty();
            assertThat(toolNames(built)).containsExactlyInAnyOrder("previous_notes", "write_note");
            assertThat(grantNames(built)).isEmpty();
          });
    }

    @Test
    void no_docker_means_no_container_tools_at_all() {
      context
          .withPropertyValues("watchman.detected.systemctl=true", "watchman.detected.docker=false")
          .run(
              built -> {
                assertThat(toolNames(built)).contains("failed_units");
                assertThat(toolNames(built)).doesNotContain("containers");
                assertThat(grantNames(built))
                    .contains("restart_unit")
                    .doesNotContain("restart_container", "prune_images");
              });
    }

    @Test
    void docker_brings_its_three() {
      context
          .withPropertyValues("watchman.detected.docker=true")
          .run(
              built -> {
                assertThat(toolNames(built)).contains("containers");
                assertThat(grantNames(built)).contains("restart_container", "prune_images");
              });
    }

    @Test
    void either_package_manager_brings_the_update_pair() {
      context
          .withPropertyValues("watchman.detected.dnf=true")
          .run(
              built -> {
                assertThat(toolNames(built)).contains("updates_pending");
                assertThat(grantNames(built)).contains("apply_updates");
              });
    }

    @Test
    void fstrim_is_what_brings_the_deferred_tool() {
      context
          .withPropertyValues("watchman.detected.fstrim=true")
          .run(built -> assertThat(toolNames(built)).contains("long_job"));
    }

    @Test
    void without_fstrim_there_is_no_long_job() {
      context.run(built -> assertThat(toolNames(built)).doesNotContain("long_job"));
    }
  }

  /**
   * The pretend host: a runner that never starts a process, and the {@code watchman.*} properties
   * bound as they are in the application.
   *
   * <p>Deliberately NOT {@code @Configuration}: the application's own component scan covers this
   * package, test classes included, and a stereotype here would put a fake {@code CommandRunner}
   * into every {@code @SpringBootTest} in the module. Registered explicitly by {@code
   * withUserConfiguration}, a lite configuration is all this needs to be.
   */
  @EnableConfigurationProperties(WatchmanProperties.class)
  static class TestHost {

    @Bean
    CommandRunner commandRunner() {
      return new FakeRunner();
    }
  }
}
