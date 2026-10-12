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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;
import org.jwcarman.nessy.api.Tokens;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Writes what a lab run measured: a JSON line per run, and a summary per variant. */
final class Report {

  private final Path file;
  private final String provider;
  private final String model;
  private final int size;

  Report(Path file, String provider, String model, int size) {
    this.file = file;
    this.provider = provider;
    this.model = model;
    this.size = size;
  }

  /** Appends one line for {@code run}, creating the file and its directory if need be. */
  void write(RunRecord run) {
    ObjectNode line = Schemas.MAPPER.createObjectNode();
    line.put("provider", provider);
    line.put("model", model);
    line.put("variant", run.variant().label());
    line.put("size", size);
    line.put("task_id", run.taskId());
    line.put("kind", run.kind().label());
    line.put("run", run.runIndex());
    line.put("success", run.success());
    line.put("outcome", run.ending().label());
    line.put("catalog_calls", run.catalogCalls());
    line.put("wrong_calls", run.wrongCalls());
    line.put("meta_calls", run.metaCalls());
    line.put("unloaded_call", run.unloadedCalls());
    line.put("unknown_call", run.unknownCalls());
    line.put("rounds", run.rounds());
    putTokens(line, "input_tokens", run.usage().input());
    putTokens(line, "output_tokens", run.usage().output());
    putTokens(line, "cache_read_tokens", run.usage().cacheRead());
    putTokens(line, "cache_write_tokens", run.usage().cacheWrite());
    putTokens(line, "reasoning_tokens", run.usage().reasoning());
    ArrayNode models = line.putArray("usage_models");
    run.usage().models().forEach(models::add);
    line.put("wall_millis", run.wallMillis());
    line.put("max_offered", run.maxOffered());
    line.put("answer", run.answer());
    line.put("note", run.note());
    ArrayNode calls = line.putArray("calls");
    run.calls().forEach(calls::add);
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(
          file,
          Schemas.MAPPER.writeValueAsString(line) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new IllegalStateException("could not write " + file + ": " + e.getMessage(), e);
    }
  }

  private static void putTokens(ObjectNode line, String name, Tokens tokens) {
    switch (tokens) {
      case Tokens.Counted(int count) -> line.put(name, count);
      case Tokens.Uncounted _ -> line.putNull(name);
    }
  }

  /**
   * The summary table, then the Usage of each variant, summed and per successful task: everything
   * the variant spent, failed runs included, divided by the number of runs that succeeded. A count
   * is shown as the number or as {@code unreported}; it is never shown as a bare token total.
   */
  static String summary(List<RunRecord> runs, String model) {
    Map<Variant, List<RunRecord>> byVariant = new EnumMap<>(Variant.class);
    for (RunRecord run : runs) {
      byVariant.computeIfAbsent(run.variant(), v -> new ArrayList<>()).add(run);
    }
    StringBuilder text = new StringBuilder();
    text.append(
        "variant  success       wrong  meta  rounds  offered  wall ms  unloaded  unknown\n");
    for (var entry : byVariant.entrySet()) {
      List<RunRecord> own = entry.getValue();
      long successes = own.stream().filter(RunRecord::success).count();
      text.append(
          String.format(
              Locale.ROOT,
              "%-7s  %-12s  %5.1f  %4.1f  %6.1f  %7.1f  %7.0f  %8d  %7d%n",
              entry.getKey().label(),
              "%d/%d (%d%%)".formatted(successes, own.size(), 100 * successes / own.size()),
              mean(own, RunRecord::wrongCalls),
              mean(own, RunRecord::metaCalls),
              mean(own, RunRecord::rounds),
              mean(own, RunRecord::maxOffered),
              mean(own, run -> (int) run.wallMillis()),
              own.stream().mapToInt(RunRecord::unloadedCalls).sum(),
              own.stream().mapToInt(RunRecord::unknownCalls).sum()));
    }
    text.append(
        "\nwrong, meta, rounds, offered and wall ms are means per run; offered is the most tools"
            + " offered on any one call.\n");
    text.append("\nUsage (").append(model).append("), summed over every call of every run:\n");
    for (var entry : byVariant.entrySet()) {
      String label = entry.getKey().label();
      List<RunRecord> own = entry.getValue();
      Tally total = Tally.EMPTY;
      long successes = 0;
      for (RunRecord run : own) {
        total = total.plus(run.usage());
        if (run.success()) {
          successes++;
        }
      }
      text.append("  ").append(label).append(" total: ").append(counts(total, 1)).append('\n');
      text.append("  ").append(label).append(" per successful task: ");
      text.append(successes == 0 ? "no run succeeded" : counts(total, successes));
      text.append('\n');
    }
    text.append(
        "A count a vendor did not report is shown as unreported, not zero; where only some calls"
            + " reported it, the sum covers those.\n");
    return text.toString();
  }

  private static double mean(List<RunRecord> runs, ToIntFunction<RunRecord> of) {
    return runs.stream().mapToInt(of).average().orElse(0);
  }

  private static String counts(Tally tally, long divisor) {
    return "input %s, output %s, cache read %s, cache write %s, reasoning %s"
        .formatted(
            shown(tally.input(), divisor),
            shown(tally.output(), divisor),
            shown(tally.cacheRead(), divisor),
            shown(tally.cacheWrite(), divisor),
            shown(tally.reasoning(), divisor));
  }

  private static String shown(Tokens tokens, long divisor) {
    return switch (tokens) {
      case Tokens.Counted(int count) -> Long.toString(Math.round((double) count / divisor));
      case Tokens.Uncounted _ -> "unreported";
    };
  }
}
