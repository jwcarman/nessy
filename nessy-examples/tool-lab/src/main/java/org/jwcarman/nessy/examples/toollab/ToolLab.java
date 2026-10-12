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

import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.anthropic.AnthropicCacheTtl;
import org.jwcarman.nessy.inference.anthropic.AnthropicInferenceProvider;
import org.jwcarman.nessy.inference.anthropic.AnthropicProperties;
import org.jwcarman.nessy.inference.anthropic.AnthropicThinkingType;
import org.jwcarman.nessy.inference.gemini.GeminiInferenceProvider;
import org.jwcarman.nessy.inference.openai.OpenAiChatInferenceProvider;

/**
 * A command-line lab that measures how well a model picks and calls tools when it is shown the
 * whole catalog, a one-line catalog, or a search, and what each costs in Usage.
 *
 * <p>The model is reached as the engine reaches it, through an {@link InferenceProvider} built from
 * the environment: {@code ANTHROPIC_API_KEY}, {@code OPENAI_API_KEY}, {@code XAI_API_KEY} or {@code
 * GEMINI_API_KEY} (or {@code GOOGLE_API_KEY}). A key is never printed.
 */
public final class ToolLab {

  /** The providers {@code --provider} accepts. */
  static final List<String> PROVIDERS = List.of("anthropic", "openai", "xai", "gemini", "lmstudio");

  /** Where a local OpenAI-compatible server listens unless {@code --base-url} says otherwise. */
  static final String LMSTUDIO_URL = "http://localhost:1234/v1";

  private static final Duration TRANSPORT = Duration.ofMinutes(6);
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private static final String USAGE =
      """
      usage: ToolLab --provider (anthropic|openai|xai|gemini|lmstudio) --model NAME
                     [--variants v0,v1,v2] [--size 100] [--tasks 10] [--runs 1]
                     [--out DIR] [--max-tokens 2048] [--base-url URL (lmstudio)]
                     [--thinking adaptive|disabled|between_tools (anthropic)]
                     [--probe-unoffered]""";

  private ToolLab() {}

  /** Runs the lab. Exit status 2 for a command line it cannot use, 1 for a run that failed. */
  public static void main(String[] args) {
    Settings settings;
    InferenceProvider provider;
    try {
      settings = Settings.parse(args);
      provider = provider(settings);
    } catch (IllegalArgumentException | IllegalStateException refused) {
      System.err.println(refused.getMessage());
      System.err.println(USAGE);
      System.exit(2);
      return;
    }
    try {
      if (settings.probeUnoffered()) {
        Probe.run(
            provider, new InferenceOptions(settings.model(), settings.maxTokens()), System.out);
      } else {
        run(settings, provider, System.out, Models.DEFAULT_PAUSE);
      }
    } catch (RuntimeException failed) {
      System.err.println("the run failed: " + failed.getMessage());
      System.exit(1);
      return;
    }
    System.exit(0);
  }

  /**
   * Runs every selected task under every selected variant, {@code settings.runs()} times each,
   * writing a line per run to a file in {@code settings.out()} and printing the summary.
   *
   * @param pause the first pause before a call that threw is tried again
   */
  static List<RunRecord> run(
      Settings settings, InferenceProvider provider, PrintStream out, Duration pause) {
    Catalog catalog = Catalog.of(settings.size());
    InferenceOptions options = new InferenceOptions(settings.model(), settings.maxTokens());
    Runner runner = new Runner(provider, options, catalog, pause);
    List<Task> tasks = Tasks.load().subList(0, settings.tasks());
    Report report =
        new Report(
            settings.out().resolve("tool-lab-" + LocalDateTime.now().format(STAMP) + ".jsonl"),
            settings.provider(),
            settings.model(),
            settings.size());
    out.printf(
        Locale.ROOT,
        "%s %s: %d tasks x %d variants x %d runs over %d tools%n",
        settings.provider(),
        settings.model(),
        tasks.size(),
        settings.variants().size(),
        settings.runs(),
        catalog.tools().size());
    List<RunRecord> runs = new ArrayList<>();
    for (Task task : tasks) {
      for (Variant variant : settings.variants()) {
        for (int run = 0; run < settings.runs(); run++) {
          RunRecord record = runner.run(task, variant, run);
          report.write(record);
          runs.add(record);
          out.printf(
              Locale.ROOT,
              "%s %s run %d: %s, %s, %d rounds, %d wrong%n",
              task.id(),
              variant.label(),
              run,
              record.success() ? "ok" : "FAILED",
              record.ending().label(),
              record.rounds(),
              record.wrongCalls());
        }
      }
    }
    out.println();
    out.print(Report.summary(runs, settings.model()));
    return List.copyOf(runs);
  }

  /** The provider {@code settings} names, built from the environment. */
  static InferenceProvider provider(Settings settings) {
    return switch (settings.provider()) {
      case "anthropic" ->
          // Every call in a run shares the system prompt and the tools, so the vendor's cache is
          // on.
          AnthropicInferenceProvider.of(
              c -> {
                c.fromEnv()
                    .timeout(TRANSPORT)
                    .property(AnthropicProperties.CACHE_TTL, AnthropicCacheTtl.FIVE_MINUTES);
                if (settings.thinking() != null) {
                  c.property(AnthropicProperties.THINKING_TYPE, thinking(settings.thinking()));
                }
              });
      case "openai" -> OpenAiChatInferenceProvider.of(c -> c.fromEnv().timeout(TRANSPORT));
      case "lmstudio" ->
          OpenAiChatInferenceProvider.of(
              c ->
                  // A local server ignores the key, but the client insists on one.
                  c.apiKey("lm-studio")
                      .baseUrl(settings.baseUrl() == null ? LMSTUDIO_URL : settings.baseUrl())
                      .vendor("lmstudio")
                      .timeout(TRANSPORT));
      case "xai" ->
          OpenAiChatInferenceProvider.of(
              c ->
                  c.apiKey(environment("XAI_API_KEY"))
                      .baseUrl("https://api.x.ai/v1")
                      .vendor("x_ai")
                      .timeout(TRANSPORT));
      case "gemini" -> GeminiInferenceProvider.of(c -> c.fromEnv().timeout(TRANSPORT));
      default ->
          throw new IllegalArgumentException(
              "unknown provider '%s'; choose one of %s".formatted(settings.provider(), PROVIDERS));
    };
  }

  /**
   * The thinking type named. {@code enabled} is not offered: it needs a budget, and the lab has no
   * option for one.
   */
  private static AnthropicThinkingType thinking(String name) {
    List<AnthropicThinkingType> offered =
        List.of(
            AnthropicThinkingType.ADAPTIVE,
            AnthropicThinkingType.DISABLED,
            AnthropicThinkingType.BETWEEN_TOOLS);
    return offered.stream()
        .filter(type -> type.name().equalsIgnoreCase(name))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "unknown thinking '%s'; choose one of %s".formatted(name, offered)));
  }

  private static String environment(String variable) {
    String value = System.getenv(variable);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(variable + " is not set");
    }
    return value;
  }
}
