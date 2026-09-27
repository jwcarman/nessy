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
package org.jwcarman.nessy.engine.harness.direct;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryLocks;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * The first GenAI telemetry the direct door has ever had (design record {@code
 * 2026-09-25-locks-as-plumbing}, §4f-§4g): once it performs through {@link
 * org.jwcarman.nessy.engine.effect.EffectHandlers}, a turn on this door reports the same spans a
 * turn on the queued door does.
 */
class DirectHarnessObservabilityTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final ToolName LOOKUP = new ToolName("lookup");

  private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
  private final ObservationRegistry observations = ObservationRegistry.create();

  record Lookup(String id) {}

  private static final Tool<Lookup> ECHO =
      new Tool<>() {
        @Override
        public Class<Lookup> inputType() {
          return Lookup.class;
        }

        @Override
        public ToolName name() {
          return LOOKUP;
        }

        @Override
        public String description() {
          return "looks something up";
        }

        @Override
        public Awaited<ToolResult> call(ToolCallRequest<Lookup> request) {
          return Awaited.ready(ToolResult.ok(new Block.Text("found it")));
        }
      };

  private String tag(String spanName, String operation, String key) {
    return stopped.stream()
        .filter(c -> c.getName().equals(spanName))
        .filter(c -> operation.equals(operationOf(c)))
        .map(c -> c.getLowCardinalityKeyValue(key))
        .filter(java.util.Objects::nonNull)
        .map(KeyValue::getValue)
        .findFirst()
        .orElse(null);
  }

  private static String operationOf(Observation.Context context) {
    KeyValue value = context.getLowCardinalityKeyValue("gen_ai.operation.name");
    return value == null ? null : value.getValue();
  }

  @Test
  void a_turn_with_a_tool_call_reports_an_inference_span_and_a_tool_span() {
    observations
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStop(Observation.Context context) {
                stopped.add(context);
              }
            });

    InferenceProvider model =
        new InferenceProvider() {
          private int calls;

          @Override
          public InferenceResult infer(
              org.jwcarman.nessy.inference.InferenceRequest request,
              org.jwcarman.nessy.inference.InferenceNarrator narrator) {
            calls++;
            return calls == 1
                ? new InferenceResult.Actions(
                    List.of(
                        new Block.ToolCall(
                            new org.jwcarman.nessy.api.tool.CallId("c1"),
                            LOOKUP,
                            "{\"id\":\"1\"}")),
                    Usage.unreported("a-model"))
                : new InferenceResult.Answer(
                    List.of(new Block.Text("done")), Usage.unreported("a-model"));
          }
        };

    DirectHarness<String, String> harness =
        DefaultDirectHarnessFactory.of(
                f ->
                    f.backend(
                            new FixedDirectBackend(
                                new InMemoryLocks(),
                                new InMemoryAgentEvents(
                                    new JacksonCodecFactory(JsonMapper.builder().build())),
                                new InMemoryPayloads(
                                    new JacksonCodecFactory(JsonMapper.builder().build()))))
                        .provider(model)
                        .schemas(new VictoolsJsonSchemaGenerator())
                        .mapper(JsonMapper.builder().build())
                        .observations(observations))
            .<String>create(
                TYPE,
                c -> {
                  c.systemPrompt("You are terse.")
                      .inputRenderer(said -> List.of(new Block.Text(said)))
                      .inference(in -> in.model("a-model"));
                  c.tool(ECHO, t -> t.approver(Approver.allow()));
                });

    harness.ask(AgentId.random(), "look it up");

    assertThat(tag("gen_ai.client.operation.duration", "chat", "gen_ai.request.model"))
        .as("the same span the queued door's inference makes")
        .isEqualTo("a-model");
    assertThat(tag("gen_ai.client.operation.duration", "execute_tool", "gen_ai.tool.name"))
        .as("the same span the queued door's tool call makes")
        .isEqualTo("lookup");

    // The span the other two hang from. Without it they were roots of their own: the model call,
    // the tool call and the context assembly each began a separate trace, so a dashboard showed a
    // turn as several unrelated things that happened to be near each other in time.
    assertThat(tag("gen_ai.client.operation.duration", "invoke_agent", "gen_ai.agent.name"))
        .as("the turn itself is a span, named the way semconv names an agent invocation")
        .isEqualTo(TYPE.value());

    // And this is the proof they are related rather than merely present: identity is put on the
    // invoke_agent span alone, and the model call copies it off whatever observation is current
    // when it starts. It can only read this if it started inside that one.
    assertThat(tag("gen_ai.client.operation.duration", "chat", "gen_ai.agent.name"))
        .as("the model call inherited the turn's identity, so it ran inside the turn's span")
        .isEqualTo(TYPE.value());

    // What answered, which is not always what was asked for: a vendor resolves an alias to a dated
    // build, and the counts beside it are priced against that one. Reported even though this call
    // counted nothing -- it still reached a model, and can still say which.
    assertThat(tag("gen_ai.client.operation.duration", "chat", "gen_ai.response.model"))
        .as("the model that answered is named, not only the one that was asked for")
        .isEqualTo("a-model");
  }
}
