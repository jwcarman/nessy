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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.TurnDecision;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.TurnPolicy;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentTurns;
import org.jwcarman.nessy.backend.inmemory.InMemoryChapters;
import org.jwcarman.nessy.backend.inmemory.InMemoryLeases;
import org.jwcarman.nessy.backend.inmemory.InMemoryLocks;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DirectHarnessTrajectoryTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final ToolName LOOKUP = new ToolName("lookup");

  record Lookup(String id) {}

  private static final Tool<Lookup> TOOL =
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

  /** Every pair of calls is one lookup, then an answer. */
  private static InferenceProvider lookupThenAnswer() {
    return new InferenceProvider() {
      private int calls;

      @Override
      public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
        calls++;
        return calls % 2 == 1
            ? new InferenceResult.Actions(
                List.of(new Block.ToolCall(new CallId("c" + calls), LOOKUP, "{\"id\":\"1\"}")),
                Usage.unreported("a-model"))
            : new InferenceResult.Answer(
                List.of(new Block.Text("found it")), Usage.unreported("a-model"));
      }
    };
  }

  private final InMemoryAgentTurns turns = new InMemoryAgentTurns();
  private final AgentEvents events =
      new InMemoryAgentEvents(new JacksonCodecFactory(JsonMapper.builder().build()));

  private DirectHarness<String, String> harness(
      ObservationRegistry observations, Consumer<HarnessConfig<?>> more) {
    JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
    return DefaultDirectHarnessFactory.of(
            f ->
                f.backend(
                        new FixedDirectBackend(
                            new InMemoryLocks(),
                            events,
                            new InMemoryPayloads(codecs),
                            new InMemoryChapters(codecs),
                            new InMemoryLeases(),
                            turns))
                    .provider(ProviderId.of("test"), lookupThenAnswer())
                    .schemas(new VictoolsJsonSchemaGenerator())
                    .mapper(JsonMapper.builder().build())
                    .observations(observations))
        .<String>create(
            TYPE,
            c -> {
              c.systemPrompt("You are terse.")
                  .inputRenderer(said -> List.of(new Block.Text(said)))
                  .inference(in -> in.provider("test").model("a-model"));
              c.tool(TOOL, t -> t.approver(Approver.allow()));
              more.accept(c);
            });
  }

  @Test
  void a_turn_through_the_direct_door_leaves_one_row_whose_bounds_are_its_events() {
    DirectHarness<String, String> harness = harness(ObservationRegistry.NOOP, c -> {});
    AgentId agent = new AgentId(UUID.randomUUID());
    harness.ask(agent, "look up 7");
    List<AgentTurn> rows = turns.of(TYPE, agent);
    assertThat(rows).hasSize(1);
    AgentTurn row = rows.getFirst();
    List<AgentEvent> story = events.readAll(TYPE, agent);
    assertThat(story.getFirst()).isInstanceOf(AgentEvent.TurnStarted.class);
    assertThat(row.turn().value()).isEqualTo(story.getFirst().seq().value());
    assertThat(row.endingSeq()).isEqualTo(story.getLast().seq());
    assertThat(story)
        .allSatisfy(
            e ->
                assertThat(e.seq().value()).isBetween(row.turn().value(), row.endingSeq().value()));
    assertThat(row.outcome()).isEqualTo(TurnOutcome.ANSWERED);
    assertThat(row.rounds()).isEqualTo(1);
    assertThat(row.toolCalls()).isEqualTo(1);
  }

  @Test
  void two_asks_with_different_inputs_and_the_same_path_share_a_trajectory() {
    DirectHarness<String, String> harness = harness(ObservationRegistry.NOOP, c -> {});
    AgentId agent = new AgentId(UUID.randomUUID());
    harness.ask(agent, "look up Bob");
    harness.ask(agent, "look up Bill");
    List<AgentTurn> rows = turns.of(TYPE, agent);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).trajectory()).isEqualTo(rows.get(1).trajectory());
    assertThat(rows.get(0).turn().value()).isLessThan(rows.get(1).turn().value());
  }

  @Test
  void the_invoke_agent_span_carries_the_trajectory() {
    List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
    ObservationRegistry observations = ObservationRegistry.create();
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
    DirectHarness<String, String> harness = harness(observations, c -> {});
    AgentId agent = new AgentId(UUID.randomUUID());
    harness.ask(agent, "look up 7");
    List<Observation.Context> turnSpans =
        stopped.stream()
            .filter(c -> c.getName().equals("gen_ai.client.operation.duration"))
            .filter(
                c -> {
                  KeyValue operation = c.getLowCardinalityKeyValue("gen_ai.operation.name");
                  return operation != null && operation.getValue().equals("invoke_agent");
                })
            .toList();
    assertThat(turnSpans).hasSize(1);
    KeyValue hash = turnSpans.getFirst().getHighCardinalityKeyValue("nessy.trajectory.hash");
    assertThat(hash).isNotNull();
    assertThat(hash.getValue()).isEqualTo(turns.of(TYPE, agent).getFirst().trajectory().hash());
  }

  @Test
  void a_turn_the_policy_stops_leaves_a_row_that_says_stopped() {
    TurnPolicy stopAfterTheFirstCall =
        (stats, now) ->
            stats.modelCalls() >= 1
                ? new TurnDecision.FailTurn("enough")
                : new TurnDecision.Continue();
    DirectHarness<String, String> harness =
        harness(ObservationRegistry.NOOP, c -> c.turnPolicy(stopAfterTheFirstCall));
    AgentId agent = new AgentId(UUID.randomUUID());
    harness.ask(agent, "look up 7");
    List<AgentTurn> rows = turns.of(TYPE, agent);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().outcome()).isEqualTo(TurnOutcome.STOPPED);
    assertThat(rows.getFirst().rounds()).isEqualTo(1);
  }
}
