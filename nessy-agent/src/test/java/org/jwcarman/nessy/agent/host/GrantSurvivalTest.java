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
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.ApprovalDesk;
import org.jwcarman.nessy.agent.CallAddress;
import org.jwcarman.nessy.agent.Kinds;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.SubstrateComputations;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The spec §9 grant-survival test: the grant delivery is durable state, not a live callback — it
 * must survive the process that granted it dying before its own delivery worker ever ran. Host A
 * parks a turn on an approval, then the test grants it by writing DIRECTLY to a second {@link
 * SubstrateComputations} handle over the SAME substrate (bypassing host A's {@code ApprovalDesk} —
 * and therefore its {@code worker::nudge} — entirely), and host A is closed immediately after,
 * before its own heartbeat can ever fire. A fresh host B, built later over that same substrate,
 * knows nothing about host A; its own delivery worker's heartbeat is the only thing that ever
 * touches the grant, and it dispatches the tool and folds the result on its own.
 */
class GrantSurvivalTest {

  record RestartInput(String target) {}

  static final class RestartTool implements Tool<RestartInput> {
    @Override
    public String name() {
      return "restart_prod";
    }

    @Override
    public String description() {
      return "restarts production; requires human approval";
    }

    @Override
    public Class<RestartInput> inputType() {
      return RestartInput.class;
    }

    @Override
    public CompletionPolicy requiredCompletion() {
      return CompletionPolicy.DURABLE;
    }

    @Override
    public Awaited<ToolResult> execute(RestartInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("restarted " + input.target()));
    }
  }

  private static final ActionContributor<RestartInput, String> RESTART_ACTION =
      input -> "restart " + input.target();

  /**
   * {@code ApprovalRequest} no longer carries the full {@code CallAddress} (the whittle ruling),
   * nor {@code responseId} (identity spec §6, the continuation audit) — this test
   * white-box-rebuilds it from the request's display fields plus the committed {@code responseId},
   * read by the caller from the scope's own state, to derive the execution id the eventual tool
   * computation lands under.
   */
  private static ComputationId toolComputationFor(ApprovalRequest request, String responseId) {
    return new CallAddress(request.agentType(), request.agentId(), responseId, request.call().id())
        .execution();
  }

  @Test
  @Timeout(30)
  void aGrantWrittenBeforeItsHostsWorkerEverRunsSurvivesToAFreshHostsWorker()
      throws InterruptedException {
    var substrate = new InMemorySubstrate();
    var mapper = TestMappers.plainlyPinned();
    var call =
        new ToolCall(
            "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-eu"));

    var pumpA = new PumpedExecutor();
    var providerA = new ScriptedModel(List.of(List.of(new ModelEvent.ToolUseEmitted(call, null))));
    var requestsA = new CopyOnWriteArrayList<ApprovalRequest>();

    ApprovalRequest firstAsk;
    var harnessA =
        Nessy.harness(
            h ->
                h.type("ops")
                    .model(providerA)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(
                        ToolGrant.grant(
                            new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
                    .substrate(substrate)
                    .approvalNotifier(requestsA::add)
                    .executor(pumpA));
    try {
      harnessA.bind(AgentId.of("prod-eu")).tell("please restart prod-eu");
      pumpA.pumpUntilQuiet();
      assertThat(requestsA).hasSize(1);
      firstAsk = requestsA.getFirst();

      // Grant it by writing DIRECTLY to the substrate, bypassing harnessA.approvals() — which
      // would have nudged harness A's own worker. Harness A's heartbeat is stopped (shutdown()
      // below) before it ever has a chance to run.
      var backendOverSameSubstrate =
          new SubstrateComputations(
              substrate,
              mapper,
              Kinds.approval(AgentType.of("ops")),
              Kinds.outbox(AgentType.of("ops")));
      // A local, silently-nudging ApprovalDesk over the SAME backend/substrate — the same
      // shape harnessA.approvals() has, but with a no-op nudge, so harness A's own worker is
      // never touched by this grant.
      new ApprovalDesk(backendOverSameSubstrate, mapper, () -> {}).approve(firstAsk.id());
    } finally {
      harnessA.shutdown();
    }
    // harnessA's worker is now quiesced, having never run even once since the grant. The grant
    // delivery exists ONLY as a document in `substrate` at this point.

    assertThat(substrate.keys(Kinds.outbox(AgentType.of("ops")), 10))
        .hasSize(1); // the grant survives as durable state

    var pumpB = new PumpedExecutor();
    var providerB =
        new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("Restarted — all good."))));
    var requestsB = new CopyOnWriteArrayList<ApprovalRequest>();

    var harnessB =
        Nessy.harness(
            h ->
                h.type("ops")
                    .model(providerB)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(
                        ToolGrant.grant(
                            new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
                    .substrate(substrate)
                    .approvalNotifier(requestsB::add)
                    .executor(pumpB));
    try {
      // No new observation, no new approval — harness B knows nothing of harness A. Its own
      // heartbeat (the recovery net, spec §5) is the only thing that ever touches this grant.
      long deadline = System.currentTimeMillis() + 20_000;
      while (!substrate.keys(Kinds.outbox(AgentType.of("ops")), 10).isEmpty()
          && System.currentTimeMillis() < deadline) {
        Thread.sleep(50);
        pumpB.pumpUntilQuiet();
      }

      assertThat(substrate.keys(Kinds.outbox(AgentType.of("ops")), 10)).isEmpty();
      assertThat(requestsB).isEmpty(); // no re-ask on harness B either
    } finally {
      harnessB.shutdown();
    }

    var state = new SubstrateAgentStateStore(substrate, "prod-eu", Clock.systemUTC(), mapper);
    assertThat(state.load().phase()).isEqualTo(new Phase.Idle());

    var memory = new SubstrateMemory(substrate, "prod-eu", mapper);
    List<Message> transcript = memory.recall().messages();
    assertThat(transcript).hasSizeGreaterThanOrEqualTo(3);
    assertThat(transcript.get(2).content())
        .contains(new ToolResultBlock("c1", "restarted prod-eu", false));
  }

  static final class DeferredRestartTool implements Tool<RestartInput> {
    @Override
    public String name() {
      return "restart_prod";
    }

    @Override
    public String description() {
      return "restarts production; requires human approval; answers durably";
    }

    @Override
    public Class<RestartInput> inputType() {
      return RestartInput.class;
    }

    @Override
    public CompletionPolicy requiredCompletion() {
      return CompletionPolicy.DURABLE;
    }

    @Override
    public Awaited<ToolResult> execute(RestartInput input, ToolContext context) {
      return Awaited.deferred();
    }
  }

  /**
   * The deferred shape of the grant arm, end-to-end through real host machinery (spec §5a
   * transfer-then-dispatch): the granted tool genuinely defers, so the grant's transfer batch is
   * {@code [create tool computation, delete delivery]} rather than the immediate arm's fold-advance
   * batch. The eventual answer arrives through the normal completion door — {@link
   * org.jwcarman.nessy.agent.CompletionDesk}, addressed by the SAME deterministic {@code
   * ComputationId} the approval request's own address derives — and folds through host B exactly
   * like any other durable completion.
   */
  @Test
  @Timeout(30)
  void theDeferredGrantArmTransfersThenDispatchesAndTheEventualAnswerFolds()
      throws InterruptedException {
    var substrate = new InMemorySubstrate();
    var mapper = TestMappers.plainlyPinned();
    var call =
        new ToolCall(
            "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-eu"));

    var pumpA = new PumpedExecutor();
    var providerA = new ScriptedModel(List.of(List.of(new ModelEvent.ToolUseEmitted(call, null))));
    var requestsA = new CopyOnWriteArrayList<ApprovalRequest>();

    ApprovalRequest firstAsk;
    var harnessA =
        Nessy.harness(
            h ->
                h.type("ops")
                    .model(providerA)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(
                        ToolGrant.grant(
                            new DeferredRestartTool(),
                            RESTART_ACTION,
                            UsagePolicy.requireApproval()))
                    .substrate(substrate)
                    .approvalNotifier(requestsA::add)
                    .executor(pumpA));
    try {
      harnessA.bind(AgentId.of("prod-eu")).tell("please restart prod-eu");
      pumpA.pumpUntilQuiet();
      assertThat(requestsA).hasSize(1);
      firstAsk = requestsA.getFirst();

      var backendOverSameSubstrate =
          new SubstrateComputations(
              substrate,
              mapper,
              Kinds.approval(AgentType.of("ops")),
              Kinds.outbox(AgentType.of("ops")));
      // A local, silently-nudging ApprovalDesk over the SAME backend/substrate — the same
      // shape harnessA.approvals() has, but with a no-op nudge, so harness A's own worker is
      // never touched by this grant.
      new ApprovalDesk(backendOverSameSubstrate, mapper, () -> {}).approve(firstAsk.id());
    } finally {
      harnessA.shutdown();
    }
    // harnessA never nudged its own worker — the grant survives purely as substrate state.

    var pumpB = new PumpedExecutor();
    var providerB =
        new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("Restarted — all good."))));
    var requestsB = new CopyOnWriteArrayList<ApprovalRequest>();
    var stateA = new SubstrateAgentStateStore(substrate, "prod-eu", Clock.systemUTC(), mapper);
    var committedResponseId = ((Phase.AwaitingTools) stateA.load().phase()).responseId().value();
    var toolComputation = toolComputationFor(firstAsk, committedResponseId);

    var harnessB =
        Nessy.harness(
            h ->
                h.type("ops")
                    .model(providerB)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(
                        ToolGrant.grant(
                            new DeferredRestartTool(),
                            RESTART_ACTION,
                            UsagePolicy.requireApproval()))
                    .substrate(substrate)
                    .approvalNotifier(requestsB::add)
                    .executor(pumpB));
    try {
      // Harness B's own heartbeat picks up the grant, transfers it (create tool computation,
      // delete delivery — one batch), and dispatches the tool, which defers again — durably, this
      // time.
      long transferDeadline = System.currentTimeMillis() + 20_000;
      while (substrate
              .read(Kinds.computation(AgentType.of("ops")), toolComputation.value())
              .isEmpty()
          && System.currentTimeMillis() < transferDeadline) {
        Thread.sleep(50);
        pumpB.pumpUntilQuiet();
      }
      assertThat(substrate.read(Kinds.computation(AgentType.of("ops")), toolComputation.value()))
          .isPresent();
      assertThat(substrate.keys(Kinds.outbox(AgentType.of("ops")), 10))
          .isEmpty(); // the grant delivery is gone
      assertThat(requestsB).isEmpty(); // no re-ask

      // The eventual external answer arrives through the normal completion door.
      harnessB.completions().complete(toolComputation, ToolResult.ok("restarted prod-eu"));

      long foldDeadline = System.currentTimeMillis() + 20_000;
      var state = new SubstrateAgentStateStore(substrate, "prod-eu", Clock.systemUTC(), mapper);
      while (!(state.load().phase() instanceof Phase.Idle)
          && System.currentTimeMillis() < foldDeadline) {
        Thread.sleep(50);
        pumpB.pumpUntilQuiet();
      }
      assertThat(state.load().phase()).isEqualTo(new Phase.Idle());
    } finally {
      harnessB.shutdown();
    }

    var memory = new SubstrateMemory(substrate, "prod-eu", mapper);
    List<Message> transcript = memory.recall().messages();
    assertThat(transcript).hasSizeGreaterThanOrEqualTo(3);
    assertThat(transcript.get(2).content())
        .contains(new ToolResultBlock("c1", "restarted prod-eu", false));
  }
}
