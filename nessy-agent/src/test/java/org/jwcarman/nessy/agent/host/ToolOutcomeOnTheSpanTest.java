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
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.model.ModelEvent;

/**
 * {@code nessy.tool.outcome} on {@code execute_tool} (in-the-loop amendment §2).
 *
 * <p>The span already carried {@code nessy.tool.deferred}, which answers "is a wait coming". The
 * outcome answers a different question — "what did the BODY do" — and the two diverge on the
 * failure paths: a tool that throws after it has already deferred is {@code deferred=true} and
 * {@code outcome=failed} at once. The values are the same vocabulary {@code nessy.tool.wait} closes
 * with, so one filter reads the execution and the dwell it opened.
 */
class ToolOutcomeOnTheSpanTest {

  /** Any term: nothing in these tests clips it. */
  private static final Duration TERM = Duration.ofDays(1);

  private static final AgentId SCOPE = AgentId.of("prod-eu");
  private static final String OUTCOME = "nessy.tool.outcome";

  private final TestObservationRegistry registry = TestObservationRegistry.create();

  @AfterEach
  void tearDown() {
    HarnessTeardown.shutdownAllTracked();
  }

  private Observation.Context executionOf(String tool) {
    List<Observation.Context> captured = new ArrayList<>();
    assertThat(registry).hasHandledContextsThatSatisfy(captured::addAll);
    List<Observation.Context> found =
        captured.stream()
            .filter(context -> ("execute_tool " + tool).equals(context.getContextualName()))
            .toList();
    assertThat(found).as("execute_tool spans for '%s'", tool).hasSize(1);
    return found.getFirst();
  }

  /** One turn whose single call runs {@code tool}, driven to quiescence. */
  private void turnRunning(Tool<?> tool) {
    var pump = new PumpedExecutor();
    var call = new ToolCall("c1", tool.name(), JsonNodeFactory.instance.objectNode());
    var model =
        new ScriptedModel(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(call, null),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE, new Usage(10, 2, 0, 0))),
                List.of(
                    new ModelEvent.TextChunk("done"),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(20, 3, 0, 0)))));

    Harness<String> harness =
        Nessy.harness(
            h ->
                h.model(model)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .executor(pump)
                    .observationRegistry(registry)
                    .tools(tool));
    HarnessTeardown.track(harness);

    harness.bind(SCOPE).tell("do the thing");
    pump.pumpUntilQuiet();
  }

  @Test
  void a_tool_that_answers_carries_returned() {
    turnRunning(new EchoTool());

    Observation.Context execution = executionOf("echo");
    assertThat(execution.getLowCardinalityKeyValue(OUTCOME).getValue()).isEqualTo("returned");
    assertThat(execution.getLowCardinalityKeyValue("nessy.tool.deferred").getValue())
        .isEqualTo("false");
  }

  @Test
  void a_tool_that_throws_carries_failed() {
    turnRunning(new ExplodingTool());

    Observation.Context execution = executionOf("explode");
    assertThat(execution.getLowCardinalityKeyValue(OUTCOME).getValue()).isEqualTo("failed");
    assertThat(execution.getLowCardinalityKeyValue("error.type").getValue())
        .isEqualTo("IllegalStateException");
  }

  @Test
  void a_tool_that_defers_carries_deferred() {
    turnRunning(new DeferringTool());

    Observation.Context execution = executionOf("slow");
    assertThat(execution.getLowCardinalityKeyValue(OUTCOME).getValue()).isEqualTo("deferred");
    assertThat(execution.getLowCardinalityKeyValue("nessy.tool.deferred").getValue())
        .isEqualTo("true");
  }

  record NoInput() {}

  static final class EchoTool implements Tool<NoInput> {

    @Override
    public String name() {
      return "echo";
    }

    @Override
    public String description() {
      return "answers, for the outcome tests";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("echoed"));
    }
  }

  static final class ExplodingTool implements Tool<NoInput> {

    @Override
    public String name() {
      return "explode";
    }

    @Override
    public String description() {
      return "throws, for the outcome tests";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      throw new IllegalStateException("the disk is on fire");
    }
  }

  static final class DeferringTool implements Tool<NoInput> {

    @Override
    public String name() {
      return "slow";
    }

    @Override
    public String description() {
      return "defers, for the outcome tests";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      return Awaited.deferred((id, deadline) -> {}, TERM);
    }
  }
}
