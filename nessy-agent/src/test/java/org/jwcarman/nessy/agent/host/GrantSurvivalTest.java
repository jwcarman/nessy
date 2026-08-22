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
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.durable.DurableDecisions;
import org.jwcarman.nessy.agent.durable.SubstrateComputations;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ActionContributor;
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
    var providerA =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.ToolUseEmitted(call, null))));
    var requestsA = new CopyOnWriteArrayList<ApprovalRequest>();

    ApprovalRequest firstAsk;
    try (var hostA =
        Nessy.autonomous()
            .type("ops")
            .provider(providerA)
            .settings(TestSettings.settings())
            .grants(
                ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
            .substrate(substrate)
            .approvalNotifier(requestsA::add)
            .executor(pumpA)
            .build()) {

      hostA.post("prod-eu", "please restart prod-eu");
      pumpA.pumpUntilQuiet();
      assertThat(requestsA).hasSize(1);
      firstAsk = requestsA.getFirst();

      // Grant it by writing DIRECTLY to the substrate, bypassing hostA.approvals() — which would
      // have nudged hostA's own worker. Host A dies (the try-with-resources close below) before
      // its own heartbeat ever has a chance to run.
      var backendOverSameSubstrate = new SubstrateComputations(substrate, mapper);
      backendOverSameSubstrate.complete(firstAsk.address().approval(), DurableDecisions.granted());
    }
    // hostA is now closed: its heartbeat thread stopped, having never run even once since the
    // grant. The grant delivery exists ONLY as a document in `substrate` at this point.

    assertThat(substrate.keys("outbox", 10)).hasSize(1); // the grant survives as durable state

    var pumpB = new PumpedExecutor();
    var providerB =
        new ScriptedModelProvider(
            List.of(List.of(new ModelEvent.TextChunk("Restarted — all good."))));
    var requestsB = new CopyOnWriteArrayList<ApprovalRequest>();

    try (var hostB =
        Nessy.autonomous()
            .type("ops")
            .provider(providerB)
            .settings(TestSettings.settings())
            .grants(
                ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
            .substrate(substrate)
            .approvalNotifier(requestsB::add)
            .executor(pumpB)
            .build()) {

      // No new post, no new approval — host B knows nothing of host A. Its own heartbeat (the
      // recovery net, spec §5) is the only thing that ever touches this grant.
      long deadline = System.currentTimeMillis() + 20_000;
      while (!substrate.keys("outbox", 10).isEmpty() && System.currentTimeMillis() < deadline) {
        Thread.sleep(50);
        pumpB.pumpUntilQuiet();
      }

      assertThat(substrate.keys("outbox", 10)).isEmpty();
      assertThat(requestsB).isEmpty(); // no re-ask on host B either
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
   * org.jwcarman.nessy.agent.durable.CompletionDesk}, addressed by the SAME deterministic {@code
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
    var providerA =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.ToolUseEmitted(call, null))));
    var requestsA = new CopyOnWriteArrayList<ApprovalRequest>();

    ApprovalRequest firstAsk;
    try (var hostA =
        Nessy.autonomous()
            .type("ops")
            .provider(providerA)
            .settings(TestSettings.settings())
            .grants(
                ToolGrant.grant(
                    new DeferredRestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
            .substrate(substrate)
            .approvalNotifier(requestsA::add)
            .executor(pumpA)
            .build()) {

      hostA.post("prod-eu", "please restart prod-eu");
      pumpA.pumpUntilQuiet();
      assertThat(requestsA).hasSize(1);
      firstAsk = requestsA.getFirst();

      var backendOverSameSubstrate = new SubstrateComputations(substrate, mapper);
      backendOverSameSubstrate.complete(firstAsk.address().approval(), DurableDecisions.granted());
    }
    // hostA never nudged its own worker — the grant survives purely as substrate state.

    var pumpB = new PumpedExecutor();
    var providerB =
        new ScriptedModelProvider(
            List.of(List.of(new ModelEvent.TextChunk("Restarted — all good."))));
    var requestsB = new CopyOnWriteArrayList<ApprovalRequest>();
    var toolComputation = firstAsk.address().execution();

    try (var hostB =
        Nessy.autonomous()
            .type("ops")
            .provider(providerB)
            .settings(TestSettings.settings())
            .grants(
                ToolGrant.grant(
                    new DeferredRestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
            .substrate(substrate)
            .approvalNotifier(requestsB::add)
            .executor(pumpB)
            .build()) {

      // Host B's own heartbeat picks up the grant, transfers it (create tool computation, delete
      // delivery — one batch), and dispatches the tool, which defers again — durably, this time.
      long transferDeadline = System.currentTimeMillis() + 20_000;
      while (substrate.read("computation", toolComputation.value()).isEmpty()
          && System.currentTimeMillis() < transferDeadline) {
        Thread.sleep(50);
        pumpB.pumpUntilQuiet();
      }
      assertThat(substrate.read("computation", toolComputation.value())).isPresent();
      assertThat(substrate.keys("outbox", 10)).isEmpty(); // the grant delivery is gone
      assertThat(requestsB).isEmpty(); // no re-ask

      // The eventual external answer arrives through the normal completion door.
      hostB.completions().complete(toolComputation, ToolResult.ok("restarted prod-eu"));

      long foldDeadline = System.currentTimeMillis() + 20_000;
      var state = new SubstrateAgentStateStore(substrate, "prod-eu", Clock.systemUTC(), mapper);
      while (!(state.load().phase() instanceof Phase.Idle)
          && System.currentTimeMillis() < foldDeadline) {
        Thread.sleep(50);
        pumpB.pumpUntilQuiet();
      }
      assertThat(state.load().phase()).isEqualTo(new Phase.Idle());
    }

    var memory = new SubstrateMemory(substrate, "prod-eu", mapper);
    List<Message> transcript = memory.recall().messages();
    assertThat(transcript).hasSizeGreaterThanOrEqualTo(3);
    assertThat(transcript.get(2).content())
        .contains(new ToolResultBlock("c1", "restarted prod-eu", false));
  }
}
