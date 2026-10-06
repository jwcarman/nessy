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
package org.jwcarman.nessy.engine.harness.queued;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.JsonSchemaGenerator;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.ToolOffer;

/**
 * A feature equips every harness the queued factory makes, before the caller has a say.
 *
 * <p>The direct door has had this tier since the factory split; the queued door gets it so a jar
 * that installs itself (the notebook is the first) lands on both doors through one customizer.
 */
@Tag("container")
class QueuedHarnessFeaturesTest {

  private static final AgentType CHAT = new AgentType("chat");

  /** Answers every call and remembers each request it was handed. */
  private static final class RecordingModel implements InferenceProvider {
    private final List<InferenceRequest> requests = new CopyOnWriteArrayList<>();

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      requests.add(request);
      return new InferenceResult.Answer(List.of(new Block.Text("ok")));
    }
  }

  private static final Tool<String> PROBE =
      new Tool<>() {
        @Override
        public ToolName name() {
          return new ToolName("probe");
        }

        @Override
        public String description() {
          return "probes";
        }

        @Override
        public Class<String> inputType() {
          return String.class;
        }

        @Override
        public JsonSchema inputSchema(JsonSchemaGenerator generator) {
          return new JsonSchema("{\"type\":\"object\"}");
        }

        @Override
        public Awaited<ToolResult> call(ToolCallRequest<String> request) {
          return Awaited.ready(ToolResult.ok(new Block.Text("probed")));
        }
      };

  private final RecordingModel model = new RecordingModel();
  private EngineFixture engine;

  @AfterEach
  void stopEngine() {
    if (engine != null) {
      engine.close();
    }
  }

  @Test
  void a_feature_installs_its_tool_on_every_harness_and_the_caller_still_has_the_last_word() {
    Customizer<HarnessConfig<?>> feature =
        config -> config.tool(PROBE).instructions("from the feature");
    engine = new EngineFixture(factory -> factory.feature(feature), model);
    QueuedHarness<String> harness =
        engine
            .harnesses()
            .create(
                CHAT,
                String.class,
                config ->
                    config
                        .systemPrompt("You are a test assistant.")
                        .instructions("from the caller")
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));

    harness.tell(AgentId.random(), "hello");

    await().atMost(Duration.ofSeconds(10)).until(() -> !model.requests.isEmpty());
    InferenceRequest request = model.requests.getFirst();
    assertThat(request.toolset().offers())
        .extracting(ToolOffer::name)
        .contains(new ToolName("probe"));
    assertThat(request.systemPrompt().value())
        .as("the feature's instruction comes before the caller's, so the caller overrides")
        .containsSubsequence("from the feature", "from the caller");
  }
}
