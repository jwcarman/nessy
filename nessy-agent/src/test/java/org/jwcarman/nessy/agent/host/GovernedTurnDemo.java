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
import org.jwcarman.nessy.agent.intent.InMemoryIntentStore;
import org.jwcarman.nessy.agent.intent.Intent;
import org.jwcarman.nessy.agent.intent.IntentEnricher;
import org.jwcarman.nessy.agent.intent.IntentStore;
import org.jwcarman.nessy.agent.intent.IntentTool;
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
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.api.tool.authorization.Enrichers;
import org.jwcarman.nessy.api.tool.authorization.RiskAssessment;
import org.jwcarman.nessy.api.tool.authorization.RiskFactors;
import org.jwcarman.nessy.api.tool.authorization.RiskLevel;
import org.jwcarman.nessy.api.tool.authorization.RiskPolicies;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.ComputationStatus;
import org.jwcarman.nessy.durable.InMemoryDurableComputationBackend;
import org.jwcarman.nessy.spi.model.ModelEvent;

/**
 * The flagship (action-wave spec §3 and §7): the model declares its intent before it acts, then
 * asks to restart prod; the restart grant composes the whole gate — the declared intent, a risk
 * assessment, and the principal — and the threshold policy judges the composed context. Severity
 * HIGH parks for a human; severity VERY_HIGH is denied outright, in-band, before any approver is
 * ever asked; no risk assessment at all fails the same door closed.
 */
class GovernedTurnDemo {

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
      return Awaited.ready(ToolResult.ok("restarted " + input.target()));
    }
  }

  private static final ActionContributor<RestartInput, String> RESTART_STATEMENT =
      ActionContributor.named("restart-statement", in -> "restart " + in.target());

  private static Enricher<Object> riskAssessor(RiskLevel likelihood, RiskLevel impact) {
    RiskAssessment assessment =
        new RiskAssessment(likelihood, impact, List.of(RiskFactors.DESTRUCTIVE));
    return Enricher.named(
        "risk", (context, action) -> context.with(AuthzContext.RISK_KEY, assessment));
  }

  private static ToolGrant restartGrant(IntentStore intentStore, Enricher<Object> riskAssessor) {
    return ToolGrant.grant(
        new RestartTool(),
        RESTART_STATEMENT,
        List.of(
            new IntentEnricher(intentStore), riskAssessor, Enrichers.principal(() -> "jcarman")),
        RiskPolicies.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH));
  }

  private static ToolGrant restartGrant(
      IntentStore intentStore, List<Enricher<? super String>> enrichers) {
    return ToolGrant.grant(
        new RestartTool(),
        RESTART_STATEMENT,
        enrichers,
        RiskPolicies.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH));
  }

  private static ToolCall declareIntentCall() {
    return new ToolCall(
        "c0",
        "declare-intent",
        JsonNodeFactory.instance
            .objectNode()
            .put("intent", "restart prod-eu to clear the stuck deploy"));
  }

  private static ToolCall restartCall() {
    return new ToolCall(
        "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-eu"));
  }

  @Test
  void theModelDeclaresIntentThenTheRiskyRestartParksForApprovalAndCompletes() {
    var pump = new PumpedExecutor();
    var backend = new InMemoryDurableComputationBackend();
    var memories = new ConcurrentHashMap<String, VerbatimMemory>();
    var stores = new ConcurrentHashMap<String, InMemoryAgentStateStore>();
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var intentStore = new InMemoryIntentStore();
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(declareIntentCall(), null)),
                List.of(new ModelEvent.ToolUseEmitted(restartCall(), null)),
                List.of(new ModelEvent.TextChunk("Done — prod-eu restarted."))));

    try (var host =
        Nessy.autonomous()
            .type("ops")
            .provider(provider)
            .settings(TestSettings.settings())
            .grants(
                ToolGrant.grant(new IntentTool(intentStore), UsagePolicy.allow()),
                restartGrant(intentStore, riskAssessor(RiskLevel.HIGH, RiskLevel.HIGH)))
            .memoryFactory(id -> memories.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .storeFactory(
                id -> stores.computeIfAbsent(id, ignored -> new InMemoryAgentStateStore()))
            .backend(backend)
            .approvalNotifier(requests::add)
            .executor(pump)
            .build()) {

      System.out.println("== the model declares intent, then asks to restart prod-eu ==");
      host.post("prod-eu", "please restart prod-eu to clear the stuck deploy");
      pump.pumpUntilQuiet();

      System.out.println("intent recorded: " + intentStore.latest());
      assertThat(intentStore.latest())
          .contains(new Intent("restart prod-eu to clear the stuck deploy"));

      var slot = ComputationId.of("approval:ops:prod-eu:c1");
      System.out.println(
          "phase after park: " + stores.get("prod-eu").load().phase().getClass().getSimpleName());
      assertThat(stores.get("prod-eu").load().phase()).isInstanceOf(Phase.AwaitingTools.class);
      assertThat(backend.status(slot)).contains(ComputationStatus.PENDING);

      assertThat(requests).isNotEmpty();
      assertThat(requests).hasSize(1);
      ApprovalRequest request = requests.getFirst();
      System.out.println("approval request context: " + request.context());
      assertThat(request.address().approval()).isEqualTo(slot);
      assertThat(request.action()).isEqualTo("restart prod-eu");
      assertThat(request.context().action()).contains("restart prod-eu");
      assertThat(request.context().declaredIntent())
          .contains(new Intent("restart prod-eu to clear the stuck deploy"));
      assertThat(request.context().principal()).contains("jcarman");
      assertThat(request.context().risk())
          .contains(
              new RiskAssessment(RiskLevel.HIGH, RiskLevel.HIGH, List.of(RiskFactors.DESTRUCTIVE)));

      System.out.println("== the desk approves; the scope resumes ==");
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
      assertThat(transcript).hasSize(6);
      assertThat(transcript.get(2).content())
          .contains(new ToolResultBlock("c0", "intent recorded", false));
      assertThat(transcript.get(4).content())
          .contains(new ToolResultBlock("c1", "restarted prod-eu", false));
    }
  }

  @Test
  void aVeryHighSeverityIsDeniedInBandBeforeAnyApproverIsAsked() {
    var pump = new PumpedExecutor();
    var backend = new InMemoryDurableComputationBackend();
    var memories = new ConcurrentHashMap<String, VerbatimMemory>();
    var stores = new ConcurrentHashMap<String, InMemoryAgentStateStore>();
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var intentStore = new InMemoryIntentStore();
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(declareIntentCall(), null)),
                List.of(new ModelEvent.ToolUseEmitted(restartCall(), null)),
                List.of(new ModelEvent.TextChunk("Understood — I will not restart prod-eu."))));

    try (var host =
        Nessy.autonomous()
            .type("ops")
            .provider(provider)
            .settings(TestSettings.settings())
            .grants(
                ToolGrant.grant(new IntentTool(intentStore), UsagePolicy.allow()),
                restartGrant(intentStore, riskAssessor(RiskLevel.VERY_HIGH, RiskLevel.VERY_HIGH)))
            .memoryFactory(id -> memories.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .storeFactory(
                id -> stores.computeIfAbsent(id, ignored -> new InMemoryAgentStateStore()))
            .backend(backend)
            .approvalNotifier(requests::add)
            .executor(pump)
            .build()) {

      System.out.println("== the risk assessor reports VERY_HIGH severity ==");
      host.post("prod-eu", "please restart prod-eu to clear the stuck deploy");
      pump.pumpUntilQuiet();

      System.out.println(
          "final phase: " + stores.get("prod-eu").load().phase().getClass().getSimpleName());
      assertThat(stores.get("prod-eu").load().phase()).isEqualTo(new Phase.Idle());
      assertThat(requests).isEmpty();

      List<Message> transcript = memories.get("prod-eu").recall().messages();
      ToolResultBlock restartResult = (ToolResultBlock) transcript.get(4).content().getFirst();
      System.out.println("restart result: " + restartResult);
      assertThat(restartResult.isError()).isTrue();
      assertThat(restartResult.content())
          .contains("risk severity VERY_HIGH meets or exceeds threshold VERY_HIGH");
    }
  }

  @Test
  void withNoRiskAssessorWiredTheThresholdFailsClosed() {
    var pump = new PumpedExecutor();
    var backend = new InMemoryDurableComputationBackend();
    var memories = new ConcurrentHashMap<String, VerbatimMemory>();
    var stores = new ConcurrentHashMap<String, InMemoryAgentStateStore>();
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var intentStore = new InMemoryIntentStore();
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(declareIntentCall(), null)),
                List.of(new ModelEvent.ToolUseEmitted(restartCall(), null)),
                List.of(new ModelEvent.TextChunk("Understood — I will not restart prod-eu."))));

    try (var host =
        Nessy.autonomous()
            .type("ops")
            .provider(provider)
            .settings(TestSettings.settings())
            .grants(
                ToolGrant.grant(new IntentTool(intentStore), UsagePolicy.allow()),
                restartGrant(
                    intentStore,
                    List.of(new IntentEnricher(intentStore), Enrichers.principal(() -> "jcarman"))))
            .memoryFactory(id -> memories.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .storeFactory(
                id -> stores.computeIfAbsent(id, ignored -> new InMemoryAgentStateStore()))
            .backend(backend)
            .approvalNotifier(requests::add)
            .executor(pump)
            .build()) {

      System.out.println("== no risk assessor is wired; the threshold fails closed ==");
      host.post("prod-eu", "please restart prod-eu to clear the stuck deploy");
      pump.pumpUntilQuiet();

      assertThat(requests).isEmpty();
      List<Message> transcript = memories.get("prod-eu").recall().messages();
      ToolResultBlock restartResult = (ToolResultBlock) transcript.get(4).content().getFirst();
      System.out.println("restart result: " + restartResult);
      assertThat(restartResult.isError()).isTrue();
      assertThat(restartResult.content()).contains("no risk assessment deposited under RISK_KEY");
    }
  }
}
