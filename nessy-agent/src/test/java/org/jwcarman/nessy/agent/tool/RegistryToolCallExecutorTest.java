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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.spi.Adjudication;
import org.jwcarman.nessy.agent.spi.ApprovalRequest;
import org.jwcarman.nessy.agent.spi.Approver;
import org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
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

  static final class NeverRunTool implements Tool<EchoInput> {
    @Override
    public String name() {
      return "never_run";
    }

    @Override
    public String description() {
      return "must never execute";
    }

    @Override
    public Class<EchoInput> inputType() {
      return EchoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
      throw new AssertionError("this tool must never run");
    }
  }

  static final class ActionBlindTool implements Tool<EchoInput> {
    @Override
    public String name() {
      return "action_blind";
    }

    @Override
    public String description() {
      return "its action must never be rendered";
    }

    @Override
    public Class<EchoInput> inputType() {
      return EchoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("ran: " + input.value()));
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

  private DeferredToolCallPolicy neverParks() {
    return (parkedCall, address) -> {
      throw new AssertionError("no tool in this test defers");
    };
  }

  private AgentEvent.ToolFinished runWithApprover(
      ToolRegistry registry, ToolCall call, RecordingTurnObserver turn, Approver approver) {
    var pump = new PumpedExecutor();
    var executor =
        new RegistryToolCallExecutor(
            registry, AgentType.of("cli"), AgentId.of("cli"), turn, pump, neverParks(), approver);
    var delivered = new ArrayList<AgentEvent>();
    executor.executeTool(call, delivered::add);
    pump.pumpUntilQuiet();
    assertThat(delivered).hasSize(1);
    return (AgentEvent.ToolFinished) delivered.getFirst();
  }

  @Test
  void aDeniedCallDeliversTheDenialInBandAndNarratesIt() {
    var registry =
        ToolRegistry.of(ToolGrant.grant(new NeverRunTool(), UsagePolicy.deny("not today")));
    var call =
        new ToolCall("c1", "never_run", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var turn = new RecordingTurnObserver();
    var finished = run(registry, call, turn);
    var failed = (ToolOutcome.Failed) finished.outcome();
    assertThat(failed.error().message()).isEqualTo("not today");
    assertThat(turn.events())
        .contains(new TurnEvent.ToolCallCompleted(call, ToolResult.error("not today")));
  }

  @Test
  void aThrowingPolicyFailsClosed() {
    UsagePolicy<Object> boomingPolicy =
        UsagePolicy.of(
            (context, action) -> {
              throw new RuntimeException("boom");
            });
    var registry = ToolRegistry.of(ToolGrant.grant(new NeverRunTool(), boomingPolicy));
    var call =
        new ToolCall("c1", "never_run", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var finished = run(registry, call, new RecordingTurnObserver());
    var failed = (ToolOutcome.Failed) finished.outcome();
    assertThat(failed.error().message()).contains("authorization failed").contains("boom");
  }

  @Test
  void theStaticAllowFastPathRunsTheToolWithoutRenderingTheAction() {
    ActionContributor<EchoInput, String> mustNotRender =
        input -> {
          throw new AssertionError("no action may be rendered on the rung-0 fast path");
        };
    var registry =
        ToolRegistry.of(ToolGrant.grant(new ActionBlindTool(), mustNotRender, UsagePolicy.allow()));
    var call =
        new ToolCall("c1", "action_blind", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var finished = run(registry, call, new RecordingTurnObserver());
    assertThat(finished.outcome()).isEqualTo(new ToolOutcome.Returned(ToolResult.ok("ran: x")));
  }

  @Test
  void requireApprovalRoutesToTheApproverWithActionAndContext() {
    var requests = new ArrayList<ApprovalRequest>();
    Approver recordingApprover =
        request -> {
          requests.add(request);
          return new Adjudication.Granted();
        };
    Enricher<Object> principalEnricher =
        Enricher.named("principal", (ctx, action) -> ctx.with(AuthzContext.PRINCIPAL_KEY, "ada"));
    ActionContributor<EchoInput, String> stringValueOf = String::valueOf;
    var registry =
        ToolRegistry.of(
            ToolGrant.grant(
                new EchoTool(),
                stringValueOf,
                List.of(principalEnricher),
                UsagePolicy.requireApproval()));
    var call = new ToolCall("c1", "echo", JsonNodeFactory.instance.objectNode().put("value", "hi"));
    var finished = runWithApprover(registry, call, new RecordingTurnObserver(), recordingApprover);
    assertThat(requests).hasSize(1);
    var request = requests.getFirst();
    assertThat(request.address()).isEqualTo(new CallAddress("cli", "cli", "c1"));
    assertThat(request.action()).isEqualTo("EchoInput[value=hi]");
    assertThat(request.context().agentName()).isEqualTo("cli");
    assertThat(request.context().principal()).contains("ada");
    assertThat(finished.outcome()).isEqualTo(new ToolOutcome.Returned(ToolResult.ok("echo: hi")));
  }

  @Test
  void aRefusedAdjudicationIsInBand() {
    Approver refusingApprover = request -> new Adjudication.Refused("the desk said no");
    var registry =
        ToolRegistry.of(ToolGrant.grant(new NeverRunTool(), UsagePolicy.requireApproval()));
    var call =
        new ToolCall("c1", "never_run", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var turn = new RecordingTurnObserver();
    var finished = runWithApprover(registry, call, turn, refusingApprover);
    var failed = (ToolOutcome.Failed) finished.outcome();
    assertThat(failed.error().message()).isEqualTo("the desk said no");
    assertThat(turn.events())
        .contains(new TurnEvent.ToolCallCompleted(call, ToolResult.error("the desk said no")));
  }

  @Test
  void aSuspendedAdjudicationDeliversNothingAndNarratesNothing() {
    var slot = ComputationId.of("approval:cli:cli:c1");
    Approver suspendingApprover = request -> new Adjudication.Suspended(slot);
    var registry =
        ToolRegistry.of(ToolGrant.grant(new NeverRunTool(), UsagePolicy.requireApproval()));
    var call =
        new ToolCall("c1", "never_run", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var turn = new RecordingTurnObserver();
    var pump = new PumpedExecutor();
    var executor =
        new RegistryToolCallExecutor(
            registry,
            AgentType.of("cli"),
            AgentId.of("cli"),
            turn,
            pump,
            neverParks(),
            suspendingApprover);
    var delivered = new ArrayList<AgentEvent>();
    executor.executeTool(call, delivered::add);
    pump.pumpUntilQuiet();
    assertThat(delivered).isEmpty();
    assertThat(turn.events()).isEmpty();
  }

  @Test
  void theDefaultApproverRefusesLoudly() {
    var registry =
        ToolRegistry.of(ToolGrant.grant(new NeverRunTool(), UsagePolicy.requireApproval()));
    var call =
        new ToolCall("c1", "never_run", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var finished = run(registry, call, new RecordingTurnObserver());
    var failed = (ToolOutcome.Failed) finished.outcome();
    assertThat(failed.error().message()).isEqualTo(RegistryToolCallExecutor.APPROVAL_UNAVAILABLE);
  }
}
