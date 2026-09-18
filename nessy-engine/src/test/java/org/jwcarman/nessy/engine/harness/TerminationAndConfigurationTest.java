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
package org.jwcarman.nessy.engine.harness;

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
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.ObservationCoalescer;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceResult;

@DisplayName("Ending an agent, and the settings a harness takes")
class TerminationAndConfigurationTest {

  private static final AgentType CHAT = new AgentType("chat-ends");
  private static final AgentType FAILING = new AgentType("chat-fails");

  private final List<AgentEvent> events = new CopyOnWriteArrayList<>();
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
  void a_terminated_agent_is_announced_and_takes_no_more_observations() {
    Harness<String> harness =
        engine
            .harnesses()
            .create(
                config ->
                    config
                        .agentType(CHAT)
                        .systemPrompt("You are a test assistant.")
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness.observe(agentId, "hello");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> events.stream().anyMatch(AgentEvent.TurnEnded.class::isInstance));
    harness.terminate(agentId);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> events.stream().anyMatch(AgentEvent.Terminated.class::isInstance));
    harness.terminate(agentId); // idempotent
    harness.observe(agentId, "anyone there?");

    List<Turn> turns = engine.harnesses().histories().forAgent(CHAT, agentId).turnsFrom(0);
    assertThat(turns).hasSize(1);
  }

  @Test
  void a_turn_the_model_could_not_answer_reads_back_as_failed() {
    Harness<String> harness =
        engine
            .harnesses()
            .create(
                config ->
                    config
                        .agentType(FAILING)
                        .systemPrompt("You are a test assistant that will fail.")
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness.observe(agentId, "hello");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> events.stream().anyMatch(AgentEvent.TurnFailed.class::isInstance));

    List<Turn> turns = engine.harnesses().histories().forAgent(FAILING, agentId).turnsFrom(0);
    assertThat(turns).singleElement().extracting(Turn::result).isEqualTo(new TurnResult.Failed());
  }

  @Test
  void every_setting_is_taken_and_two_tools_under_one_name_are_refused() {
    Harness<String> configured =
        engine
            .harnesses()
            .create(
                config ->
                    config
                        .agentType(new AgentType("chat-configured"))
                        .systemPrompt("You are a test assistant.")
                        .observationCoalescer(ObservationCoalescer.keepAll())
                        .listener(AgentEventListener.none())
                        .inference(
                            in ->
                                in.model("other")
                                    .maxTokens(64)
                                    .timeout(Duration.ofSeconds(30))
                                    .retryPolicy(new RetryPolicy.Never())
                                    .context(ctx -> ctx.maxTail(5)))
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
    java.util.function.Consumer<org.jwcarman.nessy.api.HarnessConfig<String>> clash =
        config ->
            config
                .agentType(new AgentType("chat-clash"))
                .systemPrompt("You are a test assistant.")
                .tool(new PingTool())
                .tool(new PingTool());
    assertThatThrownBy(() -> harnesses.create(clash))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");

    java.util.function.Consumer<org.jwcarman.nessy.api.HarnessConfig<String>> noTail =
        config ->
            config
                .agentType(new AgentType("chat-tail"))
                .systemPrompt("You are a test assistant.")
                .inference(in -> in.context(ctx -> ctx.maxTail(0)));
    assertThatThrownBy(() -> harnesses.create(noTail))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxTail");
  }
}
