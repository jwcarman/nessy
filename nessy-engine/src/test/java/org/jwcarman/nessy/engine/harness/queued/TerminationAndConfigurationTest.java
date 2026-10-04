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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.BacklogPolicy;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.QueuedHarnessConfig;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;

@DisplayName("Ending an agent, and the settings a harness takes")
class TerminationAndConfigurationTest {

  private static final AgentType CHAT = new AgentType("chat-ends");
  private static final AgentType FAILING = new AgentType("chat-fails");

  private final List<Narration> events = new CopyOnWriteArrayList<>();
  private EngineFixture engine;

  @BeforeEach
  void start() {
    engine =
        new EngineFixture(
            (request, narrator) ->
                request.systemPrompt().value().contains("fail")
                    ? new InferenceResult.Fault(new Failure.Permanent("no"))
                    : new InferenceResult.Answer(List.of(new Block.Text("a lake monster"))),
            (type, id, event) -> events.add(event));
  }

  @AfterEach
  void stop() {
    engine.close();
  }

  private record Ping() {}

  private static final class PingTool implements Tool<Ping> {
    @Override
    public Class<Ping> inputType() {
      return Ping.class;
    }

    @Override
    public ToolName name() {
      return new ToolName("ping");
    }

    @Override
    public String description() {
      return "pings";
    }

    @Override
    public Awaited<ToolResult> call(ToolCallRequest<Ping> request) {
      return Awaited.ready(ToolResult.ok(new Block.Text("pong")));
    }
  }

  @Test
  void a_terminated_agent_is_announced_and_takes_no_more_inputs() {
    QueuedHarness<String> harness =
        engine
            .harnesses()
            .create(
                CHAT,
                config ->
                    config
                        .systemPrompt("You are a test assistant.")
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness.tell(agentId, "hello");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> events.stream().anyMatch(Narration.TurnEnding.class::isInstance));
    harness.terminate(agentId);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> events.stream().anyMatch(Narration.Terminated.class::isInstance));
    harness.terminate(agentId); // idempotent
    harness.tell(agentId, "anyone there?");

    List<Turn> turns = engine.harnesses().histories().forAgent(CHAT, agentId).turnsFrom(0);
    assertThat(turns).hasSize(1);
  }

  @Test
  void a_turn_the_model_could_not_answer_reads_back_as_failed() {
    QueuedHarness<String> harness =
        engine
            .harnesses()
            .create(
                FAILING,
                config ->
                    config
                        .systemPrompt("You are a test assistant that will fail.")
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness.tell(agentId, "hello");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> events.stream().anyMatch(Narration.TurnFailed.class::isInstance));

    List<Turn> turns = engine.harnesses().histories().forAgent(FAILING, agentId).turnsFrom(0);
    assertThat(turns).singleElement().extracting(Turn::result).isEqualTo(new TurnResult.Failed());

    // The reason travels with the narration, and for THIS door that is the only way it travels:
    // tell() returns nothing, so a watcher told only that a turn failed would have to read the
    // event stream -- a backend concern -- to find out anything more.
    assertThat(events)
        .filteredOn(Narration.TurnFailed.class::isInstance)
        .singleElement()
        .extracting(narration -> ((Narration.TurnFailed) narration).reason())
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .isNotBlank();
  }

  @Test
  void every_setting_is_taken_and_two_tools_under_one_name_are_refused() {
    QueuedHarness<String> configured =
        engine
            .harnesses()
            .create(
                new AgentType("chat-configured"),
                config ->
                    config
                        .systemPrompt("You are a test assistant.")
                        .backlogPolicy(BacklogPolicy.keepAll())
                        .listener(NarrationListener.none())
                        .inference(
                            in ->
                                in.model("other")
                                    .maxTokens(64)
                                    .timeout(Duration.ofSeconds(30))
                                    .retryPolicy(new RetryPolicy.Never())
                                    .context(ctx -> ctx.withoutChapters().maxTail(5)))
                        .effects(e -> e.pollInterval(Duration.ofMillis(100)).maxInFlight(2))
                        .tool(
                            new PingTool(),
                            binding ->
                                binding
                                    .timeout(Duration.ofSeconds(5))
                                    .retryPolicy(new RetryPolicy.Never())
                                    .approver(
                                        request ->
                                            Awaited.ready(
                                                org.jwcarman.nessy.api.tool.ApprovalResult
                                                    .approved()),
                                        terms -> terms.retryPolicy(new RetryPolicy.Never()))));
    assertThat(configured).isNotNull();

    var harnesses = engine.harnesses();
    Customizer<QueuedHarnessConfig<String>> clash =
        config ->
            config
                .systemPrompt("You are a test assistant.")
                .tool(new PingTool())
                .tool(new PingTool());
    AgentType clashType = new AgentType("chat-clash");
    assertThatThrownBy(() -> harnesses.create(clashType, clash))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");

    Customizer<QueuedHarnessConfig<String>> noTail =
        config ->
            config
                .systemPrompt("You are a test assistant.")
                .inference(in -> in.context(ctx -> ctx.maxTail(0)));
    AgentType tailType = new AgentType("chat-tail");
    assertThatThrownBy(() -> harnesses.create(tailType, noTail))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxTail");
  }

  /** Remembers the terms of every call, and refuses one name the way a clash table would. */
  private static final class Judging implements InferenceProvider {
    final List<InferenceOptions> asked = new CopyOnWriteArrayList<>();

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      asked.add(request.options());
      return new InferenceResult.Answer(List.of(new Block.Text("a lake monster")));
    }

    @Override
    public void validate(InferenceOptions options) {
      if (options.properties().containsKey("test.model")) {
        throw new IllegalArgumentException(
            "property 'test.model' names what InferenceConfig.model already decides;"
                + " remove the property");
      }
    }
  }

  @Test
  void a_queued_agent_type_s_properties_reach_the_provider_and_a_clash_fails_the_build() {
    Judging provider = new Judging();
    try (EngineFixture judged =
        new EngineFixture(provider, (type, id, event) -> events.add(event))) {
      QueuedHarness<String> harness =
          judged
              .harnesses()
              .create(
                  new AgentType("chat-properties"),
                  config ->
                      config
                          .systemPrompt("You are a test assistant.")
                          .inference(in -> in.property("openai.seed", "7"))
                          .effects(e -> e.pollInterval(Duration.ofMillis(100))));
      harness.tell(new AgentId(UUID.randomUUID()), "hello");
      await().atMost(Duration.ofSeconds(20)).until(() -> !provider.asked.isEmpty());
      assertThat(provider.asked.getFirst().properties()).containsEntry("openai.seed", "7");

      var harnesses = judged.harnesses();
      Customizer<QueuedHarnessConfig<String>> clashing =
          config ->
              config
                  .systemPrompt("You are a test assistant.")
                  .inference(in -> in.property("test.model", "gpt-4o"));
      AgentType clashType = new AgentType("chat-properties-clash");
      assertThatThrownBy(() -> harnesses.create(clashType, clashing))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "agent type 'chat-properties-clash': property 'test.model' names what"
                  + " InferenceConfig.model already decides; remove the property");
    }
  }
}
