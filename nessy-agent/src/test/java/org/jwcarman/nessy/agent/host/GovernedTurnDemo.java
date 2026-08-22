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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.durable.StoredComputations;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.store.StoredAgentStateStore;
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
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.api.tool.authorization.Enrichers;
import org.jwcarman.nessy.api.tool.authorization.Impact;
import org.jwcarman.nessy.api.tool.authorization.Likelihood;
import org.jwcarman.nessy.api.tool.authorization.RiskAssessment;
import org.jwcarman.nessy.api.tool.authorization.RiskFactors;
import org.jwcarman.nessy.api.tool.authorization.RiskLevel;
import org.jwcarman.nessy.api.tool.authorization.RiskPolicies;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.ComputationStatus;
import org.jwcarman.nessy.intent.Intent;
import org.jwcarman.nessy.intent.IntentEnricher;
import org.jwcarman.nessy.intent.IntentStore;
import org.jwcarman.nessy.intent.IntentTool;
import org.jwcarman.nessy.intent.StoredIntentStore;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

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

  private static Enricher riskAssessor(Likelihood likelihood, Impact impact) {
    RiskAssessment assessment = RiskAssessment.of(likelihood, impact, RiskFactors.DESTRUCTIVE);
    return Enricher.named("risk", context -> context.with(AuthzContext.RISK_KEY, assessment));
  }

  private static ToolGrant restartGrant(IntentStore<Intent> intentStore, Enricher riskAssessor) {
    return ToolGrant.grant(
        new RestartTool(),
        RESTART_STATEMENT,
        List.of(
            new IntentEnricher(intentStore), riskAssessor, Enrichers.principal(() -> "jcarman")),
        RiskPolicies.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH));
  }

  private static ToolGrant restartGrant(List<Enricher> enrichers) {
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
            .put("declaration", "restart prod-eu to clear the stuck deploy"));
  }

  private static ToolCall restartCall() {
    return new ToolCall(
        "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-eu"));
  }

  @Test
  void theModelDeclaresIntentThenTheRiskyRestartParksForApprovalAndCompletes() {
    var pump = new PumpedExecutor();
    var substrate = new InMemorySubstrate();
    var prodEuState =
        new StoredAgentStateStore(
            substrate, "prod-eu", Clock.systemUTC(), TestMappers.plainlyPinned());
    var backend = new StoredComputations(substrate, TestMappers.plainlyPinned());
    var memories = new ConcurrentHashMap<String, VerbatimMemory>();
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var intentStore =
        new StoredIntentStore<>(substrate, "prod-eu", Intent.class, TestMappers.plainlyPinned());
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
                ToolGrant.grant(IntentTool.freeform(intentStore), UsagePolicy.allow()),
                restartGrant(intentStore, riskAssessor(Likelihood.HIGH, Impact.HIGH)))
            .memoryFactory(id -> memories.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .substrate(substrate)
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
          "phase after park: " + prodEuState.load().phase().getClass().getSimpleName());
      assertThat(prodEuState.load().phase()).isInstanceOf(Phase.AwaitingTools.class);
      assertThat(backend.status(slot)).contains(ComputationStatus.PENDING);

      assertThat(requests).isNotEmpty();
      assertThat(requests).hasSize(1);
      ApprovalRequest request = requests.getFirst();
      System.out.println("approval request context: " + request.context());
      assertThat(request.address().approval()).isEqualTo(slot);
      assertThat(request.context().action()).contains("restart prod-eu");
      assertThat(request.context().declaredIntent())
          .contains(new Intent("restart prod-eu to clear the stuck deploy"));
      assertThat(request.context().principal()).contains("jcarman");
      assertThat(request.context().risk())
          .contains(RiskAssessment.of(Likelihood.HIGH, Impact.HIGH, RiskFactors.DESTRUCTIVE));

      System.out.println("== the desk approves; the scope resumes ==");
      host.approvals().approve(slot);
      pump.pumpUntilQuiet();

      System.out.println("final phase: " + prodEuState.load().phase().getClass().getSimpleName());
      assertThat(prodEuState.load().phase()).isEqualTo(new Phase.Idle());
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
    var substrate = new InMemorySubstrate();
    var prodEuState =
        new StoredAgentStateStore(
            substrate, "prod-eu", Clock.systemUTC(), TestMappers.plainlyPinned());
    var backend = new StoredComputations(substrate, TestMappers.plainlyPinned());
    var memories = new ConcurrentHashMap<String, VerbatimMemory>();
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var intentStore =
        new StoredIntentStore<>(substrate, "prod-eu", Intent.class, TestMappers.plainlyPinned());
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
                ToolGrant.grant(IntentTool.freeform(intentStore), UsagePolicy.allow()),
                restartGrant(intentStore, riskAssessor(Likelihood.VERY_HIGH, Impact.VERY_HIGH)))
            .memoryFactory(id -> memories.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .substrate(substrate)
            .backend(backend)
            .approvalNotifier(requests::add)
            .executor(pump)
            .build()) {

      System.out.println("== the risk assessor reports VERY_HIGH severity ==");
      host.post("prod-eu", "please restart prod-eu to clear the stuck deploy");
      pump.pumpUntilQuiet();

      System.out.println("final phase: " + prodEuState.load().phase().getClass().getSimpleName());
      assertThat(prodEuState.load().phase()).isEqualTo(new Phase.Idle());
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
    var substrate = new InMemorySubstrate();
    var backend = new StoredComputations(substrate, TestMappers.plainlyPinned());
    var memories = new ConcurrentHashMap<String, VerbatimMemory>();
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var intentStore =
        new StoredIntentStore<>(substrate, "prod-eu", Intent.class, TestMappers.plainlyPinned());
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
                ToolGrant.grant(IntentTool.freeform(intentStore), UsagePolicy.allow()),
                restartGrant(
                    List.of(new IntentEnricher(intentStore), Enrichers.principal(() -> "jcarman"))))
            .memoryFactory(id -> memories.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .substrate(substrate)
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
