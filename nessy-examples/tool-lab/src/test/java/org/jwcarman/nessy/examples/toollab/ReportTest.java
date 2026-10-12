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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.examples.toollab.Task.Kind;
import tools.jackson.databind.JsonNode;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ReportTest {

  private static RunRecord run(
      String task, Variant variant, boolean success, int wrong, int meta, int rounds, Tally usage) {
    return new RunRecord(
        task,
        Kind.SINGLE,
        variant,
        0,
        success ? Ending.ANSWER : Ending.REFUSAL,
        success,
        1,
        wrong,
        meta,
        2,
        3,
        rounds,
        usage,
        1500,
        40,
        "an answer",
        "",
        List.of("jira_get_issue({})"));
  }

  private static Tally tally(int input, int output) {
    return new Tally(
        Tokens.of(input),
        Tokens.of(output),
        Tokens.of(7),
        Tokens.none(),
        Tokens.none(),
        List.of("fake-model"));
  }

  @Nested
  class A_line_per_run {

    @Test
    void carries_the_setting_and_every_metric(@TempDir Path directory) throws Exception {
      Path file = directory.resolve("runs.jsonl");
      Report report = new Report(file, "anthropic", "fake-model", 100);

      report.write(run("t01", Variant.V2, true, 1, 2, 4, tally(900, 80)));

      List<String> lines = Files.readAllLines(file);
      assertThat(lines).hasSize(1);
      JsonNode line = Schemas.MAPPER.readTree(lines.getFirst());
      assertThat(line.path("provider").asString()).isEqualTo("anthropic");
      assertThat(line.path("model").asString()).isEqualTo("fake-model");
      assertThat(line.path("variant").asString()).isEqualTo("v2");
      assertThat(line.path("size").asInt()).isEqualTo(100);
      assertThat(line.path("task_id").asString()).isEqualTo("t01");
      assertThat(line.path("run").asInt()).isZero();
      assertThat(line.path("success").asBoolean()).isTrue();
      assertThat(line.path("outcome").asString()).isEqualTo("answer");
      assertThat(line.path("catalog_calls").asInt()).isEqualTo(1);
      assertThat(line.path("wrong_calls").asInt()).isEqualTo(1);
      assertThat(line.path("meta_calls").asInt()).isEqualTo(2);
      assertThat(line.path("unloaded_call").asInt()).isEqualTo(2);
      assertThat(line.path("unknown_call").asInt()).isEqualTo(3);
      assertThat(line.path("rounds").asInt()).isEqualTo(4);
      assertThat(line.path("input_tokens").asInt()).isEqualTo(900);
      assertThat(line.path("output_tokens").asInt()).isEqualTo(80);
      assertThat(line.path("cache_read_tokens").asInt()).isEqualTo(7);
      assertThat(line.path("wall_millis").asLong()).isEqualTo(1500);
      assertThat(line.path("max_offered").asInt()).isEqualTo(40);
    }

    @Test
    void writes_a_count_nobody_reported_as_null_not_zero(@TempDir Path directory) throws Exception {
      Path file = directory.resolve("runs.jsonl");
      Report report = new Report(file, "lmstudio", "local", 20);

      report.write(run("t01", Variant.V0, true, 0, 0, 1, tally(10, 5)));

      JsonNode line = Schemas.MAPPER.readTree(Files.readAllLines(file).getFirst());
      assertThat(line.has("cache_write_tokens")).isTrue();
      assertThat(line.path("cache_write_tokens").isNull()).isTrue();
      assertThat(line.path("reasoning_tokens").isNull()).isTrue();
    }

    @Test
    void appends_one_line_each_and_creates_the_directory(@TempDir Path directory) throws Exception {
      Path file = directory.resolve("deeper").resolve("runs.jsonl");
      Report report = new Report(file, "openai", "m", 100);

      report.write(run("t01", Variant.V0, true, 0, 0, 1, tally(1, 1)));
      report.write(run("t02", Variant.V1, false, 0, 0, 1, tally(1, 1)));

      assertThat(Files.readAllLines(file)).hasSize(2);
    }
  }

  @Nested
  class The_summary {

    private final List<RunRecord> runs =
        List.of(
            run("t01", Variant.V0, true, 0, 0, 2, tally(1000, 100)),
            run("t02", Variant.V0, false, 2, 0, 4, tally(3000, 300)),
            run("t01", Variant.V1, true, 0, 1, 3, tally(400, 40)),
            run("t02", Variant.V1, true, 1, 3, 5, tally(600, 60)));

    @Test
    void has_a_row_per_variant_with_the_success_rate_as_a_fraction() {
      String summary = Report.summary(runs, "fake-model");

      assertThat(summary).contains("v0").contains("v1");
      assertThat(summary).contains("1/2").contains("2/2");
    }

    @Test
    void gives_the_means_of_wrong_meta_rounds_and_offered_tools() {
      String summary = Report.summary(runs, "fake-model");

      String v1 = lineStarting(summary, "v1");
      assertThat(v1).contains("0.5").contains("2.0").contains("4.0").contains("40.0");
    }

    @Test
    void totals_usage_in_five_counts_and_names_the_model() {
      String summary = Report.summary(runs, "fake-model");

      assertThat(summary).contains("Usage (fake-model)");
      String v0 = lineStarting(summary, "v0 total");
      assertThat(v0)
          .contains("input 4000")
          .contains("output 400")
          .contains("cache read 14")
          .contains("cache write unreported")
          .contains("reasoning unreported");
    }

    @Test
    void gives_usage_per_successful_task() {
      String summary = Report.summary(runs, "fake-model");

      assertThat(lineStarting(summary, "v0 per successful task"))
          .contains("input 4000")
          .contains("output 400");
      assertThat(lineStarting(summary, "v1 per successful task"))
          .contains("input 500")
          .contains("output 50");
    }

    @Test
    void says_so_when_no_run_succeeded() {
      String summary =
          Report.summary(List.of(run("t01", Variant.V0, false, 0, 0, 1, tally(1, 1))), "m");

      assertThat(lineStarting(summary, "v0 per successful task")).contains("no run succeeded");
    }

    private String lineStarting(String summary, String start) {
      return summary
          .lines()
          .map(String::strip)
          .filter(line -> line.startsWith(start))
          .findFirst()
          .orElseThrow();
    }
  }
}
