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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.ApprovalRequest;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
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
import org.jwcarman.nessy.durable.ComputationStatus;
import org.jwcarman.nessy.durable.InMemoryDurableComputationBackend;
import org.jwcarman.nessy.spi.model.ModelEvent;

/**
 * The flagship (the-doors plan, Task 9): an autonomous agent asks to restart prod, the policy says
 * RequireApproval, the call suspends into an approval slot, every instance dies — and an approve at
 * the desk redrives the scope on a fresh instance, the gate finds the decision, the tool finally
 * runs, and the turn completes. The model never knew anyone hesitated.
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
    var backend = new InMemoryDurableComputationBackend();
    var memories = new ConcurrentHashMap<String, VerbatimMemory>();
    var stores = new ConcurrentHashMap<String, InMemoryAgentStateStore>();
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
            .memoryFactory(id -> memories.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .storeFactory(
                id -> stores.computeIfAbsent(id, ignored -> new InMemoryAgentStateStore()))
            .backend(backend)
            .approvalNotifier(requests::add)
            .executor(pump)
            .build()) {

      System.out.println("== the model asks to restart prod-eu ==");
      host.post("prod-eu", "please restart prod-eu");
      pump.pumpUntilQuiet();

      var slot = ComputationId.of("approval:ops:prod-eu:c1");
      System.out.println(
          "phase after park: " + stores.get("prod-eu").load().phase().getClass().getSimpleName());
      assertThat(stores.get("prod-eu").load().phase()).isInstanceOf(Phase.AwaitingTools.class);
      assertThat(backend.status(slot)).contains(ComputationStatus.PENDING);
      assertThat(requests).hasSize(1);
      assertThat(requests.getFirst().action()).isEqualTo("restart prod-eu");
      assertThat(requests.getFirst().address().approval()).isEqualTo(slot);

      System.out.println("== hours pass; every instance is garbage; any node may answer ==");
      host.approvals().approve(slot);
      pump.pumpUntilQuiet();

      System.out.println(
          "final phase: " + stores.get("prod-eu").load().phase().getClass().getSimpleName());
      assertThat(stores.get("prod-eu").load().phase()).isEqualTo(new Phase.Idle());
      List<Message> transcript = memories.get("prod-eu").recall().messages();
      System.out.println("transcript:");
      transcript.forEach(
          m ->
              System.out.println(
                  "  "
                      + m.role()
                      + ": "
                      + m.content().stream().map(b -> b.getClass().getSimpleName()).toList()));
      assertThat(transcript).hasSize(4);
      assertThat(transcript.get(2).content())
          .contains(new ToolResultBlock("c1", "restarted prod-eu", false));
    }
  }

  @Test
  void aDenialArrivesInBandAndTheModelReacts() {
    var pump = new PumpedExecutor();
    var backend = new InMemoryDurableComputationBackend();
    var memories = new ConcurrentHashMap<String, VerbatimMemory>();
    var stores = new ConcurrentHashMap<String, InMemoryAgentStateStore>();
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
            .memoryFactory(id -> memories.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .storeFactory(
                id -> stores.computeIfAbsent(id, ignored -> new InMemoryAgentStateStore()))
            .backend(backend)
            .approvalNotifier(requests::add)
            .executor(pump)
            .build()) {

      System.out.println("== the model asks to restart prod-eu ==");
      host.post("prod-eu", "please restart prod-eu");
      pump.pumpUntilQuiet();

      var slot = ComputationId.of("approval:ops:prod-eu:c1");
      assertThat(stores.get("prod-eu").load().phase()).isInstanceOf(Phase.AwaitingTools.class);
      assertThat(requests).hasSize(1);

      System.out.println("== the desk says no; the denial arrives in-band ==");
      host.approvals().deny(slot, "not during business hours");
      pump.pumpUntilQuiet();

      System.out.println(
          "final phase: " + stores.get("prod-eu").load().phase().getClass().getSimpleName());
      assertThat(stores.get("prod-eu").load().phase()).isEqualTo(new Phase.Idle());
      List<Message> transcript = memories.get("prod-eu").recall().messages();
      assertThat(transcript).hasSize(4);
      assertThat(transcript.get(2).content())
          .contains(new ToolResultBlock("c1", "not during business hours", true));
    }
  }
}
