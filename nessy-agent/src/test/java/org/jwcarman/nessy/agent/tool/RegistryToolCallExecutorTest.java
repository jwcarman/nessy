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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.ApprovalRouting;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.Routing;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.agent.AgentType;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.api.turn.TurnEvent;

class RegistryToolCallExecutorTest {

  /** The harness ceilings, as HarnessConfig sets them (deferral-by-callback spec §5). */
  private static final Duration APPROVAL_CEILING = Duration.ofDays(7);

  private static final Duration TOOL_CEILING = Duration.ofDays(1);

  /** Any deadline: these tests are about routing, not about when a wait ends. */
  private static final Instant DEADLINE = Instant.parse("2030-01-01T00:00:00Z");

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

  /** Returns a deferral; its callback records whatever id the handoff door mints for it. */
  static final class ParkingTool implements Tool<EchoInput> {
    ComputationId handedOut;

    Duration term = Duration.ofHours(2);

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
      return Awaited.deferred((id, deadline) -> handedOut = id, term);
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

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final ContinuumClient<Approval, ApprovalRouting> approvalClient =
      TestApprovalClients.client("approval/cli", mapper);
  private final ContinuumClient<ToolResult, Routing> toolClient =
      TestToolClients.client("tool/cli", mapper);

  private RegistryToolCallExecutor executorOver(
      ToolRegistry registry, RecordingTurnObserver turn, PumpedExecutor pump) {
    return new RegistryToolCallExecutor(
        registry,
        AgentType.of("cli"),
        AgentId.of("cli"),
        turn,
        pump,
        approvalClient,
        toolClient,
        mapper,
        ObservationRegistry.NOOP,
        () -> null,
        APPROVAL_CEILING,
        TOOL_CEILING);
  }

  /** The run door: every event it delivered, in order. */
  private List<AgentEvent> runDelivering(
      ToolRegistry registry, ToolCall call, RecordingTurnObserver turn) {
    var pump = new PumpedExecutor();
    var delivered = new ArrayList<AgentEvent>();
    executorOver(registry, turn, pump).runTool(call, RESPONSE_ID, delivered::add);
    pump.pumpUntilQuiet();
    return List.copyOf(delivered);
  }

  private AgentEvent.ToolFinished run(
      ToolRegistry registry, ToolCall call, RecordingTurnObserver turn) {
    List<AgentEvent> delivered = runDelivering(registry, call, turn);
    assertThat(delivered).hasSize(1);
    return (AgentEvent.ToolFinished) delivered.getFirst();
  }

  /** The ask door: every event it delivered, in order. */
  private List<AgentEvent> seek(ToolRegistry registry, ToolCall call, RecordingTurnObserver turn) {
    var pump = new PumpedExecutor();
    var delivered = new ArrayList<AgentEvent>();
    executorOver(registry, turn, pump).seekApproval(call, RESPONSE_ID, delivered::add);
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

  /**
   * The run door creates nothing at all (deferral-by-callback spec §9a): a deferring tool yields
   * one {@code ToolCallDeferralRequested} carrying the callback and the term it asked for, and no
   * id, because none exists yet. The tool's own callback has not run either — nobody outside knows
   * anything until the handoff door below folds the park.
   */
  @Test
  void a_deferring_tool_yields_a_deferral_request_carrying_its_term_and_no_id() {
    var call =
        new ToolCall("c1", "park_me", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var tool = new ParkingTool();
    var turn = new RecordingTurnObserver();

    var delivered = runDelivering(ToolRegistry.of(tool), call, turn);

    assertThat(delivered).hasSize(1);
    assertThat(delivered.getFirst())
        .isInstanceOfSatisfying(
            AgentEvent.ToolCallDeferralRequested.class,
            requested -> {
              assertThat(requested.call()).isEqualTo(call);
              assertThat(requested.term()).isEqualTo(tool.term);
            });
    assertThat(tool.handedOut).isNull();
    assertThat(turn.events()).isEmpty();
  }

  /**
   * The handoff door is where a computation is created, and it clips the tool's term to the
   * harness's ceiling before it does (spec §5). What the callback is told is the deadline Continuum
   * actually stamped, which is also what rides the fold.
   */
  @Test
  void the_handoff_door_creates_the_computation_runs_the_callback_and_folds_the_park() {
    var call =
        new ToolCall("c1", "park_me", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var tool = new ParkingTool();
    var pump = new PumpedExecutor();
    var delivered = new ArrayList<AgentEvent>();

    executorOver(ToolRegistry.of(tool), new RecordingTurnObserver(), pump)
        .deferToolCall(
            call, (id, deadline) -> tool.handedOut = id, tool.term, RESPONSE_ID, delivered::add);
    pump.pumpUntilQuiet();

    assertThat(tool.handedOut).isNotNull();
    assertThat(delivered).hasSize(1);
    assertThat(delivered.getFirst())
        .isInstanceOfSatisfying(
            AgentEvent.ToolCallDeferred.class,
            parked -> assertThat(parked.tool()).isEqualTo(tool.handedOut));
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
    // A denial narrates BOTH: the decision, and the completion it amounts to — the reducer turns it
    // into the error result the model reads, so the call is finished, and a finished call is a
    // completion whatever finished it.
    assertThat(turn.events())
        .containsExactly(
            new TurnEvent.ToolCallDecided(call, Approval.denied("not today")),
            new TurnEvent.ToolCallCompleted(call, ToolResult.error("not today")));
  }

  @Test
  void anApprovalNarratesOnlyTheDecisionBecauseThatCallHasNotRunYet() {
    var registry = ToolRegistry.of(ToolGrant.grant(new EchoTool(), Approvers.allow()));
    var call = new ToolCall("c1", "echo", JsonNodeFactory.instance.objectNode().put("value", "hi"));
    var turn = new RecordingTurnObserver();

    seek(registry, call, turn);

    assertThat(turn.events())
        .containsExactly(new TurnEvent.ToolCallDecided(call, Approval.approved()));
  }

  @Test
  void anUnknownToolsDenialNarratesItsCompletionToo() {
    var call = new ToolCall("c1", "nope", JsonNodeFactory.instance.objectNode());
    var turn = new RecordingTurnObserver();

    seek(ToolRegistry.of(new EchoTool()), call, turn);

    assertThat(turn.events()).isNotEmpty();
    assertThat(turn.events())
        .anySatisfy(
            event ->
                assertThat(event)
                    .isInstanceOfSatisfying(
                        TurnEvent.ToolCallCompleted.class,
                        completed -> {
                          assertThat(completed.result().isError()).isTrue();
                          assertThat(completed.result().text()).contains("unknown tool");
                        }));
  }

  /**
   * The run door never consults an approver (approval-lifecycle spec §4): the answer is already a
   * fact in the phase by the time this effect is dispatched, so asking again would be a second
   * judgment on a decided call. The grant's approver throws AND records; the tool must still run.
   */
  @Test
  void runToolNeverConsultsTheApprover() {
    var consulted = new ArrayList<ApprovalRequest>();
    Approver mustNotBeAsked =
        context -> {
          consulted.add(context.request());
          throw new AssertionError("runTool must never consult an approver");
        };
    var registry = ToolRegistry.of(ToolGrant.grant(new EchoTool(), mustNotBeAsked));
    var call = new ToolCall("c1", "echo", JsonNodeFactory.instance.objectNode().put("value", "hi"));

    var finished = run(registry, call, new RecordingTurnObserver());

    assertThat(consulted).isEmpty();
    assertThat(finished.outcome()).isEqualTo(new ToolOutcome.Returned(ToolResult.ok("echo: hi")));
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
  void a_deferring_approver_yields_a_deferral_request_and_the_ask_creates_nothing() {
    var registry = ToolRegistry.of(ToolGrant.grant(new NeverRunTool(), Approvers.defer()));
    var call =
        new ToolCall("c1", "never_run", JsonNodeFactory.instance.objectNode().put("value", "x"));
    var turn = new RecordingTurnObserver();

    var folded = seek(registry, call, turn);

    assertThat(folded).hasSize(1);
    // The ask ASKS: a callback and a term, and no id, because nothing has been created yet.
    assertThat(folded.getFirst()).isInstanceOf(AgentEvent.ApprovalDeferralRequested.class);
    assertThat(turn.events()).isEmpty();
  }
}
