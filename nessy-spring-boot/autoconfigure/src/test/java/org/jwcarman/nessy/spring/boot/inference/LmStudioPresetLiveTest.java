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
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * Whether the {@code lmstudio} preset actually answers, against LM Studio running on this machine.
 *
 * <p>Builds the {@code lmstudio} provider through the catalogue, the way an application would, then
 * drives it with a real direct harness and a tool the question cannot be answered without --
 * proving the preset's request shape survives a real tool-calling round trip, not just that it
 * starts.
 *
 * <pre>{@code
 * ./mvnw -q -pl :nessy-spring-boot-autoconfigure test -Dnessy.excludedGroups= -Dtest=LmStudioPresetLiveTest
 * }</pre>
 */
@Tag("live")
@DisplayName("The lmstudio preset, against a real LM Studio server")
class LmStudioPresetLiveTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final String MODEL = "qwen/qwen3-coder-30b";

  /** A local model can be slow to a first token; five minutes is generous, not tight. */
  private static final Duration INFERENCE_TIMEOUT = Duration.ofMinutes(5);

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

  private InferenceProvider lmStudioProvider() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InferenceProvidersAutoConfiguration.class))
            .withBean(ObservationRegistry.class, ObservationRegistry::create)
            .withPropertyValues("nessy.providers.lmstudio.enabled=true");
    InferenceProvider[] holder = new InferenceProvider[1];
    runner.run(context -> holder[0] = context.getBean("lmstudio", InferenceProvider.class));
    return holder[0];
  }

  private DirectHarness<String, String> harness(
      CountingDaysUntilTool tool, Customizer<DirectHarnessConfig<String>> extra) {
    ObjectMapper mapper = JsonMapper.builder().build();
    DefaultDirectHarnessFactory factory =
        DefaultDirectHarnessFactory.of(
            f ->
                f.backend(new InMemoryDirectBackend(new JacksonCodecFactory(mapper)))
                    .provider(ProviderId.of("lmstudio"), lmStudioProvider())
                    .schemas(new VictoolsJsonSchemaGenerator())
                    .mapper(mapper)
                    .inference(ProviderId.of("lmstudio"), InferenceOptions.of(MODEL)));
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

  @Test
  @DisplayName(
      "a question that needs the tool comes back answered, and the tool was actually called")
  void a_question_needing_the_tool_is_answered_and_the_tool_is_called() {
    CountingDaysUntilTool tool = new CountingDaysUntilTool();
    DirectHarness<String, String> harness = harness(tool, c -> {});

    Outcome<String> outcome =
        harness.ask(
            AgentId.random(),
            "How many whole days from today until 2030-01-01? Use the days_until tool.");

    assertThat(outcome).isInstanceOf(Outcome.Answered.class);
    assertThat(tool.callCount()).isGreaterThanOrEqualTo(1);
    Outcome.Answered<String> answered = (Outcome.Answered<String>) outcome;
    assertThat(answered.stats().spent()).isInstanceOf(Tokens.Counted.class);
  }

  @Test
  @DisplayName("forced to answer after one call, the turn still answers")
  void forced_to_answer_after_one_call_the_turn_still_answers() {
    CountingDaysUntilTool tool = new CountingDaysUntilTool();
    DirectHarness<String, String> harness =
        harness(tool, c -> c.turnPolicy(TurnPolicy.calls(1, 2)));

    Outcome<String> outcome =
        harness.ask(
            AgentId.random(),
            "How many whole days from today until 2030-01-01? Use the days_until tool.");

    assertThat(outcome).isInstanceOf(Outcome.Answered.class);
  }
}
