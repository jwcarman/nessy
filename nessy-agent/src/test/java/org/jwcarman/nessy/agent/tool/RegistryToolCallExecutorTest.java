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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.durable.ComputationApprover;
import org.jwcarman.nessy.agent.durable.ComputationDeferredToolCallPolicy;
import org.jwcarman.nessy.agent.durable.SubstrateComputations;
import org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.TestMappers;
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
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.ToolInvocationId;
import org.jwcarman.nessy.spi.approval.Adjudication;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.approval.Approver;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

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

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Ping.class, name = "Ping"),
    @JsonSubTypes.Type(value = Pong.class, name = "Pong")
  })
  sealed interface Command permits Ping, Pong {}

  record Ping(String note) implements Command {}

  record Pong(String note) implements Command {}

  static final class DeclareTool implements Tool<Command> {
    @Override
    public String name() {
      return "declare";
    }

    @Override
    public String description() {
      return "declares a command";
    }

    @Override
    public Class<Command> inputType() {
      return Command.class;
    }

    @Override
    public Awaited<ToolResult> execute(Command input, ToolContext context) {
      return Awaited.ready(
          ToolResult.ok(
              switch (input) {
                case Ping ping -> "ping:" + ping.note();
                case Pong pong -> "pong:" + pong.note();
              }));
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

  private static final ModelResponseId RESPONSE_ID = ModelResponseId.of("r1");

  private AgentEvent.ToolFinished run(
      ToolRegistry registry, ToolCall call, RecordingTurnObserver turn) {
    var pump = new PumpedExecutor();
    var executor =
        new RegistryToolCallExecutor(
            registry,
            AgentType.of("cli"),
            AgentId.of("cli"),
            turn,
            pump,
            TestMappers.plainlyPinned());
    var delivered = new ArrayList<AgentEvent>();
    executor.executeTool(call, RESPONSE_ID, delivered::add);
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
  void aSealedInputToolBindsAGoodDeclarationAndRuns() {
    var arguments = JsonNodeFactory.instance.objectNode().put("type", "Ping").put("note", "hi");
    var call = new ToolCall("c1", "declare", arguments);
    var finished = run(ToolRegistry.of(new DeclareTool()), call, new RecordingTurnObserver());
    assertThat(finished.outcome()).isEqualTo(new ToolOutcome.Returned(ToolResult.ok("ping:hi")));
  }

  @Test
  void aSealedInputToolFailsInBandNamingAllLegalTypesForAnUnknownDeclaration() {
    var arguments = JsonNodeFactory.instance.objectNode().put("type", "Bogus").put("note", "hi");
    var call = new ToolCall("c1", "declare", arguments);
    var finished = run(ToolRegistry.of(new DeclareTool()), call, new RecordingTurnObserver());
    var failed = (ToolOutcome.Failed) finished.outcome();
    assertThat(failed.error().message()).contains("Ping").contains("Pong");
  }

  /**
   * The branch's central claim, made concrete: the schema shown to a model and the binding this
   * executor performs are not merely two independently-hand-written things that happen to agree —
   * they agree by construction, because both read {@code Command}'s own {@code @JsonTypeInfo}/
   * {@code @JsonSubTypes} annotations. Proven here by never writing a discriminator string in this
   * test at all: every {@code oneOf} branch is pulled out of {@code DeclareTool}'s own generated
   * schema, its discriminator {@code const} read back out of that same generated JSON, and a
   * declaration built from nothing but that reading is bound and executed through the exact
   * production path ({@link #run}, which threads {@code TestMappers.plainlyPinned()} the same way
   * {@code Nessy}'s builders thread the pinned mapper) — landing on the concrete record the schema
   * itself said it would.
   */
  @Test
  void everyOneOfBranchTheGeneratedSchemaDescribesBindsAndRunsAsThatExactShape() {
    ObjectNode schema = new DeclareTool().spec().inputSchema();
    JsonNode oneOf = schema.get("oneOf");
    assertThat(oneOf).isNotEmpty();

    for (JsonNode branch : oneOf) {
      String discriminator = branch.at("/properties/type/const").asText();
      var arguments =
          JsonNodeFactory.instance.objectNode().put("type", discriminator).put("note", "hi");
      var call = new ToolCall("c1", "declare", arguments);

      var finished = run(ToolRegistry.of(new DeclareTool()), call, new RecordingTurnObserver());

      String expectedPrefix = discriminator.equals("Ping") ? "ping" : "pong";
      assertThat(finished.outcome())
          .isEqualTo(new ToolOutcome.Returned(ToolResult.ok(expectedPrefix + ":hi")));
    }
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
            (parkedCall, address, invocation, retrySemantics, timeout, alsoCommit) ->
                new ToolExecution.Deferred(ComputationId.of("tool:test:cli:r1:c1")),
            TestMappers.plainlyPinned());
    var delivered = new ArrayList<AgentEvent>();
    executor.executeTool(call, RESPONSE_ID, delivered::add);
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
    return (parkedCall, address, invocation, retrySemantics, timeout, alsoCommit) -> {
      throw new AssertionError("no tool in this test defers");
    };
  }

  private AgentEvent.ToolFinished runWithApprover(
      ToolRegistry registry, ToolCall call, RecordingTurnObserver turn, Approver approver) {
    var pump = new PumpedExecutor();
    var executor =
        new RegistryToolCallExecutor(
            registry,
            AgentType.of("cli"),
            AgentId.of("cli"),
            turn,
            pump,
            neverParks(),
            approver,
            TestMappers.plainlyPinned());
    var delivered = new ArrayList<AgentEvent>();
    executor.executeTool(call, RESPONSE_ID, delivered::add);
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
    UsagePolicy boomingPolicy =
        UsagePolicy.of(
            context -> {
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
    Enricher principalEnricher =
        Enricher.named("principal", ctx -> ctx.with(AuthzContext.PRINCIPAL_KEY, "ada"));
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
    assertThat(request.address())
        .isEqualTo(new CallAddress("cli", "cli", RESPONSE_ID.value(), "c1"));
    assertThat(request.context().action()).contains("EchoInput[value=hi]");
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
    var computationId = ComputationId.of("approval:cli:cli:r1:c1");
    Approver suspendingApprover = request -> new Adjudication.Suspended(computationId);
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
            suspendingApprover,
            TestMappers.plainlyPinned());
    var delivered = new ArrayList<AgentEvent>();
    executor.executeTool(call, RESPONSE_ID, delivered::add);
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

  static final class ParkingDurableTool implements Tool<EchoInput> {
    @Override
    public String name() {
      return "park_durable";
    }

    @Override
    public String description() {
      return "always defers; gated behind approval";
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

  /**
   * The standalone counting-approver test: exactly one ask across the FULL arc, including a
   * staleness redrive that lands after the grant already turned the call into a durable tool
   * computation. Real {@link ComputationApprover} and {@link ComputationDeferredToolCallPolicy}
   * over a real {@link SubstrateComputations} — no test doubles standing in for the durable
   * machinery this test is actually about.
   */
  @Test
  void exactlyOneApproverNotificationSurvivesAStalenessRedriveAfterTheGrant() {
    var mapper = TestMappers.plainlyPinned();
    var backend = new SubstrateComputations(new InMemorySubstrate(), mapper);
    var notifications = new ArrayList<ApprovalRequest>();
    var approver = new ComputationApprover(backend, notifications::add, mapper);
    var deferredPolicy = new ComputationDeferredToolCallPolicy(backend, mapper);
    var registry =
        ToolRegistry.of(ToolGrant.grant(new ParkingDurableTool(), UsagePolicy.requireApproval()));
    var call =
        new ToolCall("c1", "park_durable", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var pump = new PumpedExecutor();
    var turn = new RecordingTurnObserver();
    var executor =
        new RegistryToolCallExecutor(
            registry,
            AgentType.of("cli"),
            AgentId.of("cli"),
            turn,
            pump,
            deferredPolicy,
            approver,
            mapper);
    var address = new CallAddress("cli", "cli", RESPONSE_ID.value(), "c1");

    // the first ask: suspends, notifies once
    var delivered = new ArrayList<AgentEvent>();
    executor.executeTool(call, RESPONSE_ID, delivered::add);
    pump.pumpUntilQuiet();
    assertThat(delivered).isEmpty();
    assertThat(notifications).hasSize(1);

    // the grant already ran (elsewhere, via the grant arm) and turned the call into a durable
    // tool computation — simulated directly, since this test is about the approver's count, not
    // the grant arm's own mechanics (covered by GrantSurvivalTest and AbsorptionTest).
    backend.create(
        address.execution(),
        new ToolInvocationId(RESPONSE_ID.value(), "c1"),
        new Continuation("SCOPE_RESUME", "{}"),
        Optional.empty());

    // a staleness redrive lands after the grant: the gate absorbs it via pendingComputation, before
    // the
    // approver is ever reached again
    var redelivered = new ArrayList<AgentEvent>();
    executor.executeTool(call, RESPONSE_ID, redelivered::add);
    pump.pumpUntilQuiet();

    assertThat(notifications).hasSize(1); // still exactly one ask, ever
  }
}
