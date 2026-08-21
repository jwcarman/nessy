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
package org.jwcarman.nessy.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.durable.ComputationId;

class RegistryToolCallExecutorTest {

  record EchoInput(String value) {}

  static final class EchoTool implements Tool<EchoInput> {
    @Override
    public String name() {
      return "echo";
    }

    @Override
    public String description() {
      return "echoes";
    }

    @Override
    public Class<EchoInput> inputType() {
      return EchoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("echo: " + input.value()));
    }
  }

  static final class ParkingTool implements Tool<EchoInput> {
    @Override
    public String name() {
      return "park_me";
    }

    @Override
    public String description() {
      return "always parks";
    }

    @Override
    public Class<EchoInput> inputType() {
      return EchoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
      return Awaited.deferred();
    }
  }

  private AgentEvent.ToolFinished run(
      ToolRegistry registry, ToolCall call, RecordingTurnObserver turn) {
    var pump = new PumpedExecutor();
    var executor =
        new RegistryToolCallExecutor(registry, AgentType.of("cli"), AgentId.of("cli"), turn, pump);
    var delivered = new ArrayList<AgentEvent>();
    executor.executeTool(call, delivered::add);
    pump.pumpUntilQuiet();
    assertThat(delivered).hasSize(1);
    return (AgentEvent.ToolFinished) delivered.getFirst();
  }

  @Test
  void aKnownToolExecutesAndReturns() {
    var call = new ToolCall("c1", "echo", JsonNodeFactory.instance.objectNode().put("value", "hi"));
    var finished = run(ToolRegistry.of(new EchoTool()), call, new RecordingTurnObserver());
    assertThat(finished.outcome()).isEqualTo(new ToolOutcome.Returned(ToolResult.ok("echo: hi")));
  }

  @Test
  void anUnknownToolFailsInBand() {
    var call = new ToolCall("c1", "nope", JsonNodeFactory.instance.objectNode());
    var finished = run(ToolRegistry.of(new EchoTool()), call, new RecordingTurnObserver());
    var failed = (ToolOutcome.Failed) finished.outcome();
    assertThat(failed.error().message()).contains("unknown tool").contains("nope");
  }

  @Test
  void aParkingToolFailsLoudlyInThisWiring() {
    var call =
        new ToolCall("c1", "park_me", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var finished = run(ToolRegistry.of(new ParkingTool()), call, new RecordingTurnObserver());
    var failed = (ToolOutcome.Failed) finished.outcome();
    assertThat(failed.error().message()).contains("deferred execution is unavailable");
  }

  @Test
  void aThrowingToolFailsInBandInsteadOfEscaping() {
    var boom =
        new Tool<EchoInput>() {
          @Override
          public String name() {
            return "boom";
          }

          @Override
          public String description() {
            return "throws";
          }

          @Override
          public Class<EchoInput> inputType() {
            return EchoInput.class;
          }

          @Override
          public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
            throw new IllegalStateException("kaboom");
          }
        };
    var call = new ToolCall("c1", "boom", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var finished = run(ToolRegistry.of(boom), call, new RecordingTurnObserver());
    var failed = (ToolOutcome.Failed) finished.outcome();
    assertThat(failed.error().message()).contains("kaboom");
  }

  static final class ProgressTool implements Tool<EchoInput> {
    @Override
    public String name() {
      return "progress_me";
    }

    @Override
    public String description() {
      return "reports progress once";
    }

    @Override
    public Class<EchoInput> inputType() {
      return EchoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
      context.progress("halfway");
      return Awaited.ready(ToolResult.ok("done"));
    }
  }

  @Test
  void aRunningToolsProgressNarratesAsToolCallProgressedWithItsOwnMessage() {
    var call =
        new ToolCall("c1", "progress_me", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var turn = new RecordingTurnObserver();
    run(ToolRegistry.of(new ProgressTool()), call, turn);
    assertThat(turn.events()).contains(new TurnEvent.ToolCallProgressed(call, "halfway"));
  }

  @Test
  void aSuspendingPolicyDeliversNothingAndNarratesNothing() {
    var call =
        new ToolCall("c1", "park_me", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var pump = new PumpedExecutor();
    var turn = new RecordingTurnObserver();
    var executor =
        new RegistryToolCallExecutor(
            ToolRegistry.of(new ParkingTool()),
            AgentType.of("cli"),
            AgentId.of("cli"),
            turn,
            pump,
            (parkedCall, address) ->
                new ToolExecution.Deferred(ComputationId.of("tool:test:cli:c1")));
    var delivered = new ArrayList<AgentEvent>();
    executor.executeTool(call, delivered::add);
    pump.pumpUntilQuiet();
    assertThat(delivered).isEmpty();
    assertThat(turn.events()).isEmpty();
  }

  @Test
  void theLoudDefaultSurvivesThePolicySeam() {
    var call =
        new ToolCall("c9", "park_me", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var finished = run(ToolRegistry.of(new ParkingTool()), call, new RecordingTurnObserver());
    var failed = (ToolOutcome.Failed) finished.outcome();
    assertThat(failed.error().message()).contains("deferred execution is unavailable");
  }
}
