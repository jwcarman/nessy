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
import org.jwcarman.nessy.agent.Phase;
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
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The flagship (the-doors plan, Task 9): an autonomous agent asks to restart prod, the policy says
 * RequireApproval, the call suspends into an approval computation, every instance dies — and an
 * approve at the desk redrives the scope on a fresh instance, the gate finds the decision, the tool
 * finally runs, and the turn completes. The model never knew anyone hesitated.
 */
class AutonomousApprovalDemo {

  record RestartInput(String target) {}

  static final class RestartTool implements Tool<RestartInput> {

    @Override
    public String name() {
      return "restart_prod";
    }

    @Override
    public String description() {
      return "restarts a production target; requires human approval";
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
      // Immediate! The approval gate is the wait, not the tool itself.
      return Awaited.ready(ToolResult.ok("restarted " + input.target()));
    }
  }

  private static final ActionContributor<RestartInput, String> RESTART_ACTION =
      input -> "restart " + input.target();

  @Test
  void anApprovalParksTheTurnAndTheDeskResumesIt() {
    var pump = new PumpedExecutor();
    var substrate = new InMemorySubstrate();
    var prodEuState =
        new SubstrateAgentStateStore(
            substrate, "prod-eu", Clock.systemUTC(), TestMappers.plainlyPinned());
    var backend = new SubstrateComputations(substrate, TestMappers.plainlyPinned());
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var call =
        new ToolCall(
            "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-eu"));
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(call, null)),
                List.of(new ModelEvent.TextChunk("Done — prod-eu restarted."))));

    try (var host =
        Nessy.autonomous()
            .type("ops")
            .provider(provider)
            .settings(TestSettings.settings())
            .grants(
                ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
            .substrate(substrate)
            .backend(backend)
            .approvalNotifier(requests::add)
            .executor(pump)
            .build()) {

      System.out.println("== the model asks to restart prod-eu ==");
      host.post("prod-eu", "please restart prod-eu");
      pump.pumpUntilQuiet();

      System.out.println(
          "phase after park: " + prodEuState.load().phase().getClass().getSimpleName());
      assertThat(prodEuState.load().phase()).isInstanceOf(Phase.AwaitingTools.class);
      var parkedResponseId = ((Phase.AwaitingTools) prodEuState.load().phase()).responseId();
      var computation =
          ComputationId.of("approval:ops:prod-eu:" + parkedResponseId.value() + ":c1");
      assertThat(backend.find(computation)).isPresent();
      assertThat(requests).hasSize(1);
      assertThat(requests.getFirst().context().action()).contains("restart prod-eu");
      assertThat(requests.getFirst().address().approval()).isEqualTo(computation);

      System.out.println("== hours pass; every instance is garbage; any node may answer ==");
      host.approvals().approve(computation);
      pump.pumpUntilQuiet();

      // The grant arc (durable-deliveries spec §5a, Task 3): the delivery worker reads the grant's
      // continuation directly and dispatches the call past the gate via
      // ToolCallExecutor#executeGrantedToolNow — no re-derivation, no second ask. The tool runs
      // exactly once and the turn completes; the notifier fires exactly once, on the original ask.
      System.out.println("final phase: " + prodEuState.load().phase().getClass().getSimpleName());
      assertThat(prodEuState.load().phase()).isEqualTo(new Phase.Idle());
      assertThat(requests).hasSize(1);
      assertThat(backend.find(computation)).isEmpty();
    }
  }

  @Test
  void aDenialArrivesInBandAndTheModelReacts() {
    var pump = new PumpedExecutor();
    var substrate = new InMemorySubstrate();
    var prodEuState =
        new SubstrateAgentStateStore(
            substrate, "prod-eu", Clock.systemUTC(), TestMappers.plainlyPinned());
    var backend = new SubstrateComputations(substrate, TestMappers.plainlyPinned());
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var call =
        new ToolCall(
            "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-eu"));
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(call, null)),
                List.of(new ModelEvent.TextChunk("Understood — I will not restart prod-eu."))));

    try (var host =
        Nessy.autonomous()
            .type("ops")
            .provider(provider)
            .settings(TestSettings.settings())
            .grants(
                ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
            .substrate(substrate)
            .backend(backend)
            .approvalNotifier(requests::add)
            .executor(pump)
            .build()) {

      System.out.println("== the model asks to restart prod-eu ==");
      host.post("prod-eu", "please restart prod-eu");
      pump.pumpUntilQuiet();

      assertThat(prodEuState.load().phase()).isInstanceOf(Phase.AwaitingTools.class);
      var parkedResponseId = ((Phase.AwaitingTools) prodEuState.load().phase()).responseId();
      var computation =
          ComputationId.of("approval:ops:prod-eu:" + parkedResponseId.value() + ":c1");
      assertThat(requests).hasSize(1);

      System.out.println("== the desk says no; the denial arrives in-band ==");
      host.approvals().deny(computation, "not during business hours");
      pump.pumpUntilQuiet();

      System.out.println("final phase: " + prodEuState.load().phase().getClass().getSimpleName());
      assertThat(prodEuState.load().phase()).isEqualTo(new Phase.Idle());
      List<Message> transcript =
          new SubstrateMemory(substrate, "prod-eu", TestMappers.plainlyPinned())
              .recall()
              .messages();
      assertThat(transcript).hasSize(4);
      assertThat(transcript.get(2).content())
          .contains(new ToolResultBlock("c1", "not during business hours", true));
    }
  }
}
