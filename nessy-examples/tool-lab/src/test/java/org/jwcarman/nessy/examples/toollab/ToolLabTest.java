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
package org.jwcarman.nessy.examples.toollab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ToolLabTest {

  private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

  @Nested
  class A_pilot_run {

    @Test
    void runs_each_task_under_each_variant_and_writes_a_line_per_run(@TempDir Path directory)
        throws Exception {
      Settings settings =
          Settings.parse(
              new String[] {
                "--provider",
                "lmstudio",
                "--model",
                "fake",
                "--variants",
                "v0,v1",
                "--size",
                "20",
                "--tasks",
                "2",
                "--runs",
                "2",
                "--out",
                directory.toString()
              });
      // Every run answers at once in text: the single task fails, the multi task fails.
      ScriptedProvider provider = new ScriptedProvider();
      for (int i = 0; i < 2 * 2 * 2; i++) {
        provider.then(ScriptedProvider.saying("I do not know."));
      }

      List<RunRecord> runs = ToolLab.run(settings, provider, out, Duration.ZERO);

      assertThat(runs).hasSize(8);
      Path file;
      try (Stream<Path> files = Files.list(directory)) {
        file = files.findFirst().orElseThrow();
      }
      assertThat(file.getFileName().toString()).startsWith("tool-lab-").endsWith(".jsonl");
      List<String> lines = Files.readAllLines(file);
      assertThat(lines).hasSize(8);
      JsonNode first = Schemas.MAPPER.readTree(lines.getFirst());
      assertThat(first.path("provider").asString()).isEqualTo("lmstudio");
      assertThat(first.path("model").asString()).isEqualTo("fake");
      assertThat(first.path("size").asInt()).isEqualTo(20);
    }

    @Test
    void prints_the_summary_table(@TempDir Path directory) {
      Settings settings =
          Settings.parse(
              new String[] {
                "--provider",
                "lmstudio",
                "--model",
                "fake",
                "--variants",
                "v0",
                "--size",
                "20",
                "--tasks",
                "1",
                "--out",
                directory.toString()
              });
      ScriptedProvider provider = new ScriptedProvider().then(ScriptedProvider.saying("hm"));

      ToolLab.run(settings, provider, out, Duration.ZERO);

      String printed = captured.toString(StandardCharsets.UTF_8);
      assertThat(printed).contains("v0").contains("0/1").contains("Usage (fake)");
    }

    @Test
    void stops_nothing_when_one_run_faults_and_goes_on_to_the_next(@TempDir Path directory) {
      Settings settings =
          Settings.parse(
              new String[] {
                "--provider",
                "lmstudio",
                "--model",
                "fake",
                "--variants",
                "v0",
                "--size",
                "20",
                "--tasks",
                "2",
                "--out",
                directory.toString()
              });
      ScriptedProvider provider = new ScriptedProvider();
      for (int i = 0; i < Models.ATTEMPTS; i++) {
        provider.thenThrow(new IllegalStateException("down"));
      }
      provider.then(ScriptedProvider.saying("hm"));

      List<RunRecord> runs = ToolLab.run(settings, provider, out, Duration.ZERO);

      assertThat(runs).extracting(RunRecord::ending).containsExactly(Ending.FAULT, Ending.ANSWER);
    }
  }

  @Nested
  class Building_a_provider {

    @Test
    void for_a_local_server_needs_no_key_and_makes_no_call() {
      Settings settings =
          Settings.parse(
              new String[] {
                "--provider", "lmstudio", "--model", "m", "--base-url", "http://localhost:9/v1"
              });

      assertThat(ToolLab.provider(settings)).isNotNull();
    }

    @Test
    void refuses_a_thinking_type_it_does_not_offer() {
      Settings settings =
          Settings.parse(
              new String[] {"--provider", "anthropic", "--model", "m", "--thinking", "enabled"});

      assertThatThrownBy(() -> ToolLab.provider(settings))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("enabled");
    }
  }
}
