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
package org.jwcarman.nessy.spring.boot.inference;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.api.TurnPolicy;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.inmemory.InMemoryDirectBackend;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Measures the OpenAI-shaped hosted vendors that a future Boot preset would promise, against their
 * real endpoints, so the promotion decision rests on what each one actually does rather than on
 * what its marketing page claims.
 *
 * <p>Each candidate is built the way a custom provider is today -- {@code nessy.providers.<id>.wire
 * =openai} plus a base URL, an environment-sourced key and a vendor -- or, for an existing preset,
 * by its key alone, so a wire other than {@code openai} is measured too. Either is then driven
 * through a real direct harness with a tool the question cannot be answered without, exactly like
 * {@link LmStudioPresetLiveTest}. A candidate with no key, or no model and no default, is skipped
 * rather than failed: this test measures what is reachable in the runner's environment, not what
 * should exist. Results land in {@code target/preset-measurements.md} for James to read after a
 * run; no key is ever written to that file, an assertion message, a log line, or an exception this
 * test constructs.
 *
 * <pre>{@code
 * ./mvnw -q -pl :nessy-spring-boot-autoconfigure test -Dnessy.excludedGroups= -Dtest=PresetCandidatesLiveTest
 * }</pre>
 */
@Tag("live")
@DisplayName("The OpenAI-shaped vendor candidates, against their real endpoints")
class PresetCandidatesLiveTest {

  private static final AgentType TYPE = new AgentType("chat");

  /** A hosted API is expected to be fast; two minutes is generous, not tight. */
  private static final Duration INFERENCE_TIMEOUT = Duration.ofMinutes(2);

  private static final List<Candidate> CANDIDATES =
      List.of(
          Candidate.preset("anthropic", "ANTHROPIC_API_KEY", "anthropic"),
          Candidate.preset("xai", "XAI_API_KEY", "x_ai"),
          Candidate.preset("gemini", "GEMINI_API_KEY", "gcp.gemini"),
          new Candidate("openai", "https://api.openai.com/v1", "OPENAI_API_KEY", null, "openai"),
          new Candidate(
              "groq",
              "https://api.groq.com/openai/v1",
              "GROQ_API_KEY",
              "openai/gpt-oss-20b",
              "groq"),
          new Candidate(
              "deepseek", "https://api.deepseek.com/v1", "DEEPSEEK_API_KEY", null, "deepseek"),
          new Candidate(
              "mistral", "https://api.mistral.ai/v1", "MISTRAL_API_KEY", null, "mistral_ai"),
          new Candidate(
              "openrouter",
              "https://openrouter.ai/api/v1",
              "OPENROUTER_API_KEY",
              null,
              "openrouter"),
          new Candidate(
              "together", "https://api.together.xyz/v1", "TOGETHER_API_KEY", null, "together"),
          new Candidate(
              "fireworks",
              "https://api.fireworks.ai/inference/v1",
              "FIREWORKS_API_KEY",
              null,
              "fireworks"),
          new Candidate(
              "cerebras", "https://api.cerebras.ai/v1", "CEREBRAS_API_KEY", null, "cerebras"),
          new Candidate("ollama", "http://localhost:11434/v1", "OLLAMA_API_KEY", null, "ollama"),
          new Candidate(
              "nvidia", "https://integrate.api.nvidia.com/v1", "NVIDIA_API_KEY", null, "nvidia"));

  private static final Map<String, Row> ROWS = new ConcurrentHashMap<>();

  /** One row of the measurement table: what was asked of the vendor, and what came back. */
  private record Row(String id, String model, String baseUrl, String status, String detail) {}

  /**
   * A vendor this test can reach if its key is exported; {@code defaultModel} may be absent. A
   * candidate with no {@code baseUrl} is an existing preset, reached by its key alone -- which is
   * how a wire other than {@code openai} gets measured.
   */
  private record Candidate(
      String id,
      @Nullable String baseUrl,
      String keyEnvVar,
      @Nullable String defaultModel,
      String vendor) {

    static Candidate preset(String id, String keyEnvVar, String vendor) {
      return new Candidate(id, null, keyEnvVar, null, vendor);
    }

    String shownUrl() {
      return baseUrl == null ? "(the " + id + " preset)" : baseUrl;
    }

    String modelEnvVar() {
      return id.toUpperCase(Locale.ROOT) + "_MODEL";
    }
  }

  record Input(@JsonPropertyDescription("The target date, as ISO-8601: 2026-12-25") String date) {}

  /**
   * Counts how many times the model actually reached for it, rather than answering from guesswork.
   */
  static final class CountingDaysUntilTool implements Tool<Input> {

    private final AtomicInteger calls = new AtomicInteger();

    int callCount() {
      return calls.get();
    }

    @Override
    public Class<Input> inputType() {
      return Input.class;
    }

    @Override
    public ToolName name() {
      return new ToolName("days_until");
    }

    @Override
    public String description() {
      return "Counts the whole days from today until a given ISO-8601 date.";
    }

    @Override
    public Awaited<ToolResult> call(ToolCallRequest<Input> request) {
      calls.incrementAndGet();
      long days = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(request.input().date()));
      return Awaited.ready(ToolResult.ok(new Block.Text(days + " days")));
    }
  }

  @TestFactory
  Stream<DynamicContainer> preset_candidates() {
    return CANDIDATES.stream().map(this::containerFor);
  }

  private DynamicContainer containerFor(Candidate candidate) {
    String key = System.getenv(candidate.keyEnvVar());
    if (key == null || key.isBlank()) {
      return DynamicContainer.dynamicContainer(
          candidate.id(),
          List.of(
              DynamicTest.dynamicTest(
                  "skipped: no key",
                  () ->
                      skip(
                          candidate,
                          "set " + candidate.keyEnvVar() + " to measure " + candidate.id()))));
    }
    String model = resolveModel(candidate);
    if (model == null) {
      return DynamicContainer.dynamicContainer(
          candidate.id(),
          List.of(
              DynamicTest.dynamicTest(
                  "skipped: no model",
                  () ->
                      skip(
                          candidate,
                          "set "
                              + candidate.modelEnvVar()
                              + " to measure "
                              + candidate.id()
                              + " (it has no default model)"))));
    }
    return DynamicContainer.dynamicContainer(
        candidate.id(),
        List.of(
            DynamicTest.dynamicTest(
                "a question needing the tool is answered and the tool is called",
                () -> toolCallCheck(candidate, key, model)),
            DynamicTest.dynamicTest(
                "forced to answer after one call, the turn still answers",
                () -> forcedAnswerCheck(candidate, key, model))));
  }

  private static @Nullable String resolveModel(Candidate candidate) {
    String override = System.getenv(candidate.modelEnvVar());
    if (override != null && !override.isBlank()) {
      return override;
    }
    return candidate.defaultModel();
  }

  private void skip(Candidate candidate, String message) {
    ROWS.put(
        candidate.id(), new Row(candidate.id(), "-", candidate.shownUrl(), "SKIPPED", message));
    Assumptions.assumeTrue(false, message);
  }

  private void toolCallCheck(Candidate candidate, String key, String model) {
    try {
      CountingDaysUntilTool tool = new CountingDaysUntilTool();
      DirectHarness<String, String> harness = harness(candidate, key, model, tool, c -> {});

      Outcome<String> outcome =
          harness.ask(
              AgentId.random(),
              "How many whole days from today until 2030-01-01? Use the days_until tool.");

      assertThat(outcome).isInstanceOf(Outcome.Answered.class);
      assertThat(tool.callCount()).isGreaterThanOrEqualTo(1);
      Outcome.Answered<String> answered = (Outcome.Answered<String>) outcome;
      assertThat(answered.stats().spent()).isInstanceOf(Tokens.Counted.class);
      recordSuccess(candidate, model);
    } catch (Throwable t) {
      recordFailure(candidate, model, t);
      throw t;
    }
  }

  private void forcedAnswerCheck(Candidate candidate, String key, String model) {
    try {
      CountingDaysUntilTool tool = new CountingDaysUntilTool();
      DirectHarness<String, String> harness =
          harness(candidate, key, model, tool, c -> c.turnPolicy(TurnPolicy.calls(1, 2)));

      Outcome<String> outcome =
          harness.ask(
              AgentId.random(),
              "How many whole days from today until 2030-01-01? Use the days_until tool.");

      assertThat(outcome).isInstanceOf(Outcome.Answered.class);
      recordSuccess(candidate, model);
    } catch (Throwable t) {
      recordFailure(candidate, model, t);
      throw t;
    }
  }

  private void recordSuccess(Candidate candidate, String model) {
    ROWS.merge(
        candidate.id(),
        new Row(candidate.id(), model, candidate.shownUrl(), "PASS", ""),
        (existing, fresh) -> "FAIL".equals(existing.status()) ? existing : fresh);
  }

  private void recordFailure(Candidate candidate, String model, Throwable t) {
    String detail = truncate(t.getClass().getName() + ": " + t.getMessage(), 300);
    ROWS.put(candidate.id(), new Row(candidate.id(), model, candidate.shownUrl(), "FAIL", detail));
  }

  private static String truncate(@Nullable String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() <= max ? text : text.substring(0, max);
  }

  private InferenceProvider provider(Candidate candidate, String key) {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InferenceProvidersAutoConfiguration.class))
            .withBean(ObservationRegistry.class, ObservationRegistry::create)
            .withPropertyValues(
                candidate.baseUrl() == null
                    ? new String[] {"nessy.providers." + candidate.id() + ".api-key=" + key}
                    : new String[] {
                      "nessy.providers." + candidate.id() + ".wire=openai",
                      "nessy.providers." + candidate.id() + ".base-url=" + candidate.baseUrl(),
                      "nessy.providers." + candidate.id() + ".api-key=" + key,
                      "nessy.providers." + candidate.id() + ".vendor=" + candidate.vendor()
                    });
    InferenceProvider[] holder = new InferenceProvider[1];
    runner.run(context -> holder[0] = context.getBean(candidate.id(), InferenceProvider.class));
    return holder[0];
  }

  private DirectHarness<String, String> harness(
      Candidate candidate,
      String key,
      String model,
      CountingDaysUntilTool tool,
      Customizer<DirectHarnessConfig<String>> extra) {
    ObjectMapper mapper = JsonMapper.builder().build();
    DefaultDirectHarnessFactory factory =
        DefaultDirectHarnessFactory.of(
            f ->
                f.backend(new InMemoryDirectBackend(new JacksonCodecFactory(mapper)))
                    .provider(ProviderId.of(candidate.id()), provider(candidate, key))
                    .schemas(new VictoolsJsonSchemaGenerator())
                    .mapper(mapper)
                    .inference(ProviderId.of(candidate.id()), InferenceOptions.of(model)));
    return factory.create(
        TYPE,
        c -> {
          c.systemPrompt("You are a terse assistant. Use the days_until tool for date math.")
              .inputRenderer(said -> List.of(new Block.Text(said)))
              .inference(in -> in.timeout(INFERENCE_TIMEOUT))
              .tool(tool);
          extra.customize(c);
        });
  }

  @AfterAll
  static void write_results_file() throws IOException {
    StringBuilder markdown = new StringBuilder();
    markdown.append("| id | model | base url | result | detail |\n");
    markdown.append("|---|---|---|---|---|\n");
    for (Candidate candidate : CANDIDATES) {
      Row row =
          ROWS.getOrDefault(
              candidate.id(),
              new Row(candidate.id(), "-", candidate.shownUrl(), "SKIPPED", "not run"));
      markdown
          .append("| ")
          .append(row.id())
          .append(" | ")
          .append(row.model())
          .append(" | ")
          .append(row.baseUrl())
          .append(" | ")
          .append(row.status())
          .append(" | ")
          .append(row.detail())
          .append(" |\n");
    }
    Path out = Path.of("target", "preset-measurements.md");
    Files.createDirectories(out.getParent());
    Files.writeString(out, markdown.toString());
  }
}
