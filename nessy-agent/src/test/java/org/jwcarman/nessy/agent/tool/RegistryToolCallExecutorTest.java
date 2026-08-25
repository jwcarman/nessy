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
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.spi.ApprovalContexts;
import org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalContext;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.api.turn.TurnEvent;

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
    executor.runTool(call, RESPONSE_ID, delivered::add);
    pump.pumpUntilQuiet();
    assertThat(delivered).hasSize(1);
    return (AgentEvent.ToolFinished) delivered.getFirst();
  }

  /** The ask door: every event it delivered, in order. */
  private List<AgentEvent> seek(ToolRegistry registry, ToolCall call, RecordingTurnObserver turn) {
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
    executor.seekApproval(call, RESPONSE_ID, delivered::add);
    pump.pumpUntilQuiet();
    return List.copyOf(delivered);
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
  void anUnknownToolIsDeniedAtTheAskDoor() {
    var call = new ToolCall("c1", "nope", JsonNodeFactory.instance.objectNode());

    var delivered = seek(ToolRegistry.of(new EchoTool()), call, new RecordingTurnObserver());

    assertThat(delivered).hasSize(1);
    var answered = (AgentEvent.ApprovalAnswered) delivered.getFirst();
    assertThat(((Approval.Denied) answered.answer()).reason()).contains("unknown tool");
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
  void aToolThatDefersDeliversToolDeferredCarryingTheComputationsId() {
    var call =
        new ToolCall("c1", "park_me", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var parked = ComputationId.of("tool:test:cli:r1:c1");
    var pump = new PumpedExecutor();
    var turn = new RecordingTurnObserver();
    var executor =
        new RegistryToolCallExecutor(
            ToolRegistry.of(new ParkingTool()),
            AgentType.of("cli"),
            AgentId.of("cli"),
            turn,
            pump,
            (parkedCall, address, timeout) -> new ToolExecution.Deferred(parked),
            TestMappers.plainlyPinned());
    var delivered = new ArrayList<AgentEvent>();

    executor.runTool(call, RESPONSE_ID, delivered::add);
    pump.pumpUntilQuiet();

    assertThat(delivered).containsExactly(new AgentEvent.ToolDeferred(call, parked));
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
    return (parkedCall, address, timeout) -> {
      throw new AssertionError("no tool in this test defers");
    };
  }

  @Test
  void aStaticDenialAnswersWithoutBuildingARequest() {
    Enricher mustNotRun =
        draft -> {
          throw new AssertionError("no enricher runs on the rung-0 fast path");
        };
    ActionContributor<EchoInput, String> mustNotRender =
        input -> {
          throw new AssertionError("no action is rendered on the rung-0 fast path");
        };
    var registry =
        ToolRegistry.of(
            ToolGrant.grant(
                new NeverRunTool(),
                mustNotRender,
                List.of(mustNotRun),
                Approvers.deny("not today")));
    var call =
        new ToolCall("c1", "never_run", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var turn = new RecordingTurnObserver();

    var delivered = seek(registry, call, turn);

    assertThat(delivered).hasSize(1);
    var answered = (AgentEvent.ApprovalAnswered) delivered.getFirst();
    assertThat(answered.approval()).isEmpty();
    assertThat(((Approval.Denied) answered.answer()).reason()).isEqualTo("not today");
    assertThat(turn.events())
        .contains(new TurnEvent.ToolCallDecided(call, Approval.denied("not today")));
  }

  @Test
  void theStaticAllowFastPathAnswersWithoutRunningAnEnricher() {
    Enricher mustNotRun =
        draft -> {
          throw new AssertionError("no enricher runs on the rung-0 fast path");
        };
    ActionContributor<EchoInput, String> mustNotRender =
        input -> {
          throw new AssertionError("no action is rendered on the rung-0 fast path");
        };
    var registry =
        ToolRegistry.of(
            ToolGrant.grant(
                new ActionBlindTool(), mustNotRender, List.of(mustNotRun), Approvers.allow()));
    var call =
        new ToolCall("c1", "action_blind", JsonNodeFactory.instance.objectNode().put("value", "x"));

    var delivered = seek(registry, call, new RecordingTurnObserver());

    assertThat(delivered).hasSize(1);
    var answered = (AgentEvent.ApprovalAnswered) delivered.getFirst();
    assertThat(answered.answer()).isInstanceOf(Approval.Approved.class);
  }

  @Test
  void aThrowingEnricherFailsClosedNamingTheAuthorizationStage() {
    Enricher boom =
        Enricher.named(
            "quota",
            draft -> {
              throw new IllegalStateException("boom");
            });
    ActionContributor<EchoInput, String> stringValueOf = String::valueOf;
    var registry =
        ToolRegistry.of(
            ToolGrant.grant(new NeverRunTool(), stringValueOf, List.of(boom), Approvers.defer()));
    var call =
        new ToolCall("c1", "never_run", JsonNodeFactory.instance.objectNode().put("value", "x"));

    var delivered = seek(registry, call, new RecordingTurnObserver());

    assertThat(delivered).hasSize(1);
    var answered = (AgentEvent.ApprovalAnswered) delivered.getFirst();
    assertThat(((Approval.Denied) answered.answer()).reason())
        .contains("authorization failed")
        .contains("boom");
  }

  @Test
  void theRequestTheApproverReadsCarriesTheCoordinatesTheActionAndTheFacts() {
    var seen = new ArrayList<ApprovalRequest>();
    Enricher principal =
        Enricher.named("principal", draft -> draft.deposit(ApprovalRequest.PRINCIPAL, "ada"));
    ActionContributor<EchoInput, String> stringValueOf = String::valueOf;
    Approver recording =
        context -> {
          seen.add(context.request());
          return new ApprovalOutcome.Answered(Approval.approved());
        };
    var registry =
        ToolRegistry.of(
            ToolGrant.grant(new EchoTool(), stringValueOf, List.of(principal), recording));
    var call = new ToolCall("c1", "echo", JsonNodeFactory.instance.objectNode().put("value", "hi"));

    var delivered = seek(registry, call, new RecordingTurnObserver());

    assertThat(delivered).hasSize(1);
    assertThat(seen).hasSize(1);
    ApprovalRequest request = seen.getFirst();
    assertThat(request.agentType()).isEqualTo("cli");
    assertThat(request.agentId()).isEqualTo("cli");
    assertThat(request.action()).contains("EchoInput[value=hi]");
    assertThat(request.facts().get(ApprovalRequest.PRINCIPAL)).contains("ada");
  }

  @Test
  void aDeferringApproverDeliversNothingItselfBecauseDeferAlreadyFolded() {
    var parked = ComputationId.of("approval-1");
    var folded = new ArrayList<AgentEvent>();
    var pump = new PumpedExecutor();
    var turn = new RecordingTurnObserver();
    ApprovalContexts contexts =
        (call, responseId, request, sink) ->
            new ApprovalContext() {
              @Override
              public ApprovalRequest request() {
                return request;
              }

              @Override
              public ApprovalOutcome defer() {
                sink.deliver(new AgentEvent.ApprovalDeferred(call, parked, request));
                return new ApprovalOutcome.Deferred(parked);
              }
            };
    var registry = ToolRegistry.of(ToolGrant.grant(new NeverRunTool(), Approvers.defer()));
    var executor =
        new RegistryToolCallExecutor(
            registry,
            AgentType.of("cli"),
            AgentId.of("cli"),
            turn,
            pump,
            neverParks(),
            contexts,
            TestMappers.plainlyPinned());
    var call =
        new ToolCall("c1", "never_run", JsonNodeFactory.instance.objectNode().put("value", "x"));

    executor.seekApproval(call, RESPONSE_ID, folded::add);
    pump.pumpUntilQuiet();

    assertThat(folded).hasSize(1);
    assertThat(folded.getFirst()).isInstanceOf(AgentEvent.ApprovalDeferred.class);
    assertThat(turn.events()).isEmpty();
  }

  @Test
  void aWiringWithNoContinuumBehindItCannotParkAndSaysSo() {
    var registry = ToolRegistry.of(ToolGrant.grant(new NeverRunTool(), Approvers.defer()));
    var call =
        new ToolCall("c1", "never_run", JsonNodeFactory.instance.objectNode().put("value", "x"));

    var delivered = seek(registry, call, new RecordingTurnObserver());

    assertThat(delivered).hasSize(1);
    var answered = (AgentEvent.ApprovalAnswered) delivered.getFirst();
    assertThat(((Approval.Denied) answered.answer()).reason())
        .contains("approver failed")
        .contains(RegistryToolCallExecutor.APPROVAL_UNAVAILABLE);
  }
}
