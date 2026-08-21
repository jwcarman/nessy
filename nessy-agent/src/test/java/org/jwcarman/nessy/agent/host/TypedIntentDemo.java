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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.intent.InMemoryIntentStore;
import org.jwcarman.nessy.agent.intent.IntentEnricher;
import org.jwcarman.nessy.agent.intent.IntentTool;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.api.tool.authorization.Impact;
import org.jwcarman.nessy.api.tool.authorization.IntentPolicies;
import org.jwcarman.nessy.api.tool.authorization.Likelihood;
import org.jwcarman.nessy.api.tool.authorization.RiskAssessment;
import org.jwcarman.nessy.api.tool.authorization.RiskFactors;
import org.jwcarman.nessy.api.tool.authorization.RiskLevel;
import org.jwcarman.nessy.api.tool.authorization.RiskPolicies;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.InMemoryDurableComputationBackend;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.intent.IntentStore;
import org.jwcarman.nessy.spi.model.ModelEvent;

/**
 * The flagship for the vocabulary amendment's §3 consistency-check bullet: an organization's own
 * sealed intent vocabulary, {@code OpsIntent}, rides the generic {@link IntentTool} kit end to end.
 * Three arcs, one org vocabulary:
 *
 * <ol>
 *   <li>The teaching loop — a restart attempted with no declaration is denied in-band, teaching the
 *       model to declare first; once declared and retried, the risky restart parks for a human and
 *       completes once approved.
 *   <li>The mismatch tripwire — a declared target and an attempted target disagree; an in-fixture
 *       consistency policy denies, naming both.
 *   <li>The unrepresentable declaration — a shape outside the vocabulary is rejected by the
 *       discriminator binder itself, in-band, before the intent tool ever runs; nothing is stored.
 * </ol>
 */
class TypedIntentDemo {

  sealed interface OpsIntent permits Restart, Diagnose {}

  record Restart(String target, String reason) implements OpsIntent {}

  record Diagnose(String target) implements OpsIntent {}

  record RestartInput(String target) {}

  static final class RestartTool implements Tool<RestartInput> {

    @Override
    public String name() {
      return "restart_prod";
    }

    @Override
    public String description() {
      return "restarts a production target; requires a declared intent and a risk assessment";
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
      ActionContributor.named("restart-statement", in -> "restart " + in.target());

  private static Enricher riskAssessor(Likelihood likelihood, Impact impact) {
    RiskAssessment assessment = RiskAssessment.of(likelihood, impact, RiskFactors.DESTRUCTIVE);
    return Enricher.named("risk", context -> context.with(AuthzContext.RISK_KEY, assessment));
  }

  /**
   * The org's own consistency check (vocabulary amendment §3): declared target must match acted
   * target.
   */
  private static UsagePolicy consistencyPolicy() {
    return UsagePolicy.of(
        context -> {
          Optional<OpsIntent> declared = context.declaredIntent(OpsIntent.class);
          if (declared.isEmpty()) {
            return new PolicyDecision.Allow();
          }
          return switch (declared.get()) {
            case Restart(String target, _) -> {
              String rendered = context.action(String.class).orElse("");
              yield rendered.contains(target)
                  ? new PolicyDecision.Allow()
                  : new PolicyDecision.Deny(
                      "declared intent targets \""
                          + target
                          + "\" but the action is \""
                          + rendered
                          + "\"");
            }
            case Diagnose _ -> new PolicyDecision.Allow();
          };
        });
  }

  private static ToolGrant restartGrant(
      IntentStore<OpsIntent> intentStore, Enricher riskAssessor, boolean checkConsistency) {
    List<UsagePolicy> policies =
        checkConsistency
            ? List.of(
                IntentPolicies.requireDeclared(OpsIntent.class),
                RiskPolicies.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH),
                consistencyPolicy())
            : List.of(
                IntentPolicies.requireDeclared(OpsIntent.class),
                RiskPolicies.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH));
    return ToolGrant.grant(
        new RestartTool(),
        RESTART_ACTION,
        List.of(new IntentEnricher(intentStore), riskAssessor),
        UsagePolicy.allOf(policies));
  }

  @Test
  void anUndeclaredRestartIsDeniedTeachingTheModelToDeclareThenTheApprovedRestartCompletes() {
    var pump = new PumpedExecutor();
    var backend = new InMemoryDurableComputationBackend();
    var memories = new ConcurrentHashMap<String, VerbatimMemory>();
    var stores = new ConcurrentHashMap<String, InMemoryAgentStateStore>();
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var intentStore = new InMemoryIntentStore<OpsIntent>();

    var firstAttempt =
        new ToolCall(
            "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-eu"));
    var declareCall =
        new ToolCall(
            "c2",
            "declare-intent",
            JsonNodeFactory.instance
                .objectNode()
                .put("type", "Restart")
                .put("target", "prod-eu")
                .put("reason", "stuck deploy"));
    var retryAttempt =
        new ToolCall(
            "c3", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-eu"));
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(firstAttempt, null)),
                List.of(new ModelEvent.ToolUseEmitted(declareCall, null)),
                List.of(new ModelEvent.ToolUseEmitted(retryAttempt, null)),
                List.of(new ModelEvent.TextChunk("Done — prod-eu restarted."))));

    try (var host =
        Nessy.autonomous()
            .type("ops")
            .provider(provider)
            .settings(TestSettings.settings())
            .grants(
                ToolGrant.grant(
                    new IntentTool<>(OpsIntent.class, intentStore), UsagePolicy.allow()),
                restartGrant(intentStore, riskAssessor(Likelihood.HIGH, Impact.HIGH), false))
            .memoryFactory(id -> memories.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .storeFactory(
                id -> stores.computeIfAbsent(id, ignored -> new InMemoryAgentStateStore()))
            .backend(backend)
            .approvalNotifier(requests::add)
            .executor(pump)
            .build()) {

      System.out.println("== the model asks to restart prod-eu with no declared intent ==");
      host.post("prod-eu", "please restart prod-eu");
      pump.pumpUntilQuiet();

      System.out.println("intent recorded: " + intentStore.latest());
      assertThat(intentStore.latest()).contains(new Restart("prod-eu", "stuck deploy"));

      System.out.println("== the desk approves the retried restart ==");
      var slot = ComputationId.of("approval:ops:prod-eu:c3");
      assertThat(backend.status(slot)).isPresent();
      assertThat(requests).hasSize(1);
      ApprovalRequest request = requests.getFirst();
      assertThat(request.context().declaredIntent(OpsIntent.class))
          .contains(new Restart("prod-eu", "stuck deploy"));

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
      assertThat(transcript).hasSize(8);
      ToolResultBlock firstDenial = (ToolResultBlock) transcript.get(2).content().getFirst();
      System.out.println("first denial: " + firstDenial);
      assertThat(firstDenial.isError()).isTrue();
      assertThat(firstDenial.content()).contains("declare-intent");
      assertThat(transcript.get(6).content())
          .contains(new ToolResultBlock("c3", "restarted prod-eu", false));
    }
  }

  @Test
  void aDeclaredTargetThatDoesNotMatchTheAttemptedTargetIsDeniedNamingBoth() {
    var pump = new PumpedExecutor();
    var backend = new InMemoryDurableComputationBackend();
    var memories = new ConcurrentHashMap<String, VerbatimMemory>();
    var stores = new ConcurrentHashMap<String, InMemoryAgentStateStore>();
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var intentStore = new InMemoryIntentStore<OpsIntent>();

    var declareCall =
        new ToolCall(
            "c1",
            "declare-intent",
            JsonNodeFactory.instance
                .objectNode()
                .put("type", "Restart")
                .put("target", "prod-eu")
                .put("reason", "stuck deploy"));
    var mismatchedAttempt =
        new ToolCall(
            "c2", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-us"));
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(declareCall, null)),
                List.of(new ModelEvent.ToolUseEmitted(mismatchedAttempt, null)),
                List.of(new ModelEvent.TextChunk("Understood — I will not restart prod-us."))));

    try (var host =
        Nessy.autonomous()
            .type("ops")
            .provider(provider)
            .settings(TestSettings.settings())
            .grants(
                ToolGrant.grant(
                    new IntentTool<>(OpsIntent.class, intentStore), UsagePolicy.allow()),
                restartGrant(intentStore, riskAssessor(Likelihood.HIGH, Impact.HIGH), true))
            .memoryFactory(id -> memories.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .storeFactory(
                id -> stores.computeIfAbsent(id, ignored -> new InMemoryAgentStateStore()))
            .backend(backend)
            .approvalNotifier(requests::add)
            .executor(pump)
            .build()) {

      System.out.println("== the model declares prod-eu, then tries to restart prod-us instead ==");
      host.post("prod-eu", "please restart prod-us");
      pump.pumpUntilQuiet();

      assertThat(requests).isEmpty();
      System.out.println(
          "final phase: " + stores.get("prod-eu").load().phase().getClass().getSimpleName());
      assertThat(stores.get("prod-eu").load().phase()).isEqualTo(new Phase.Idle());

      List<Message> transcript = memories.get("prod-eu").recall().messages();
      ToolResultBlock mismatchDenial = (ToolResultBlock) transcript.get(4).content().getFirst();
      System.out.println("mismatch denial: " + mismatchDenial);
      assertThat(mismatchDenial.isError()).isTrue();
      assertThat(mismatchDenial.content()).contains("prod-eu").contains("prod-us");
    }
  }

  @Test
  void anUnrepresentableDeclarationFailsInBandNamingTheLegalTypesAndStoresNothing() {
    var pump = new PumpedExecutor();
    var backend = new InMemoryDurableComputationBackend();
    var memories = new ConcurrentHashMap<String, VerbatimMemory>();
    var stores = new ConcurrentHashMap<String, InMemoryAgentStateStore>();
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var intentStore = new InMemoryIntentStore<OpsIntent>();

    var unrepresentableDeclare =
        new ToolCall(
            "c1",
            "declare-intent",
            JsonNodeFactory.instance.objectNode().put("type", "Nuke").put("target", "x"));
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(unrepresentableDeclare, null)),
                List.of(new ModelEvent.TextChunk("Understood — that shape is not supported."))));

    try (var host =
        Nessy.autonomous()
            .type("ops")
            .provider(provider)
            .settings(TestSettings.settings())
            .grants(
                ToolGrant.grant(
                    new IntentTool<>(OpsIntent.class, intentStore), UsagePolicy.allow()),
                restartGrant(intentStore, riskAssessor(Likelihood.HIGH, Impact.HIGH), false))
            .memoryFactory(id -> memories.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .storeFactory(
                id -> stores.computeIfAbsent(id, ignored -> new InMemoryAgentStateStore()))
            .backend(backend)
            .approvalNotifier(requests::add)
            .executor(pump)
            .build()) {

      System.out.println("== the model declares a shape outside the vocabulary ==");
      host.post("prod-eu", "please nuke x");
      pump.pumpUntilQuiet();

      System.out.println("intent recorded: " + intentStore.latest());
      assertThat(intentStore.latest()).isEmpty();

      System.out.println(
          "final phase: " + stores.get("prod-eu").load().phase().getClass().getSimpleName());
      assertThat(stores.get("prod-eu").load().phase()).isEqualTo(new Phase.Idle());

      List<Message> transcript = memories.get("prod-eu").recall().messages();
      ToolResultBlock bindingError = (ToolResultBlock) transcript.get(2).content().getFirst();
      System.out.println("binding error: " + bindingError);
      assertThat(bindingError.isError()).isTrue();
      assertThat(bindingError.content()).contains("Restart").contains("Diagnose");
    }
  }
}
