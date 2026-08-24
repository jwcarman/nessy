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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
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
import org.jwcarman.nessy.api.tool.authorization.Likelihood;
import org.jwcarman.nessy.api.tool.authorization.RiskAssessment;
import org.jwcarman.nessy.api.tool.authorization.RiskFactors;
import org.jwcarman.nessy.api.tool.authorization.RiskLevel;
import org.jwcarman.nessy.api.tool.authorization.RiskPolicies;
import org.jwcarman.nessy.intent.IntentEnricher;
import org.jwcarman.nessy.intent.IntentPolicies;
import org.jwcarman.nessy.intent.IntentStore;
import org.jwcarman.nessy.intent.IntentTool;
import org.jwcarman.nessy.intent.SubstrateIntentStore;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

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

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Restart.class, name = "Restart"),
    @JsonSubTypes.Type(value = Diagnose.class, name = "Diagnose")
  })
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
    var substrate = new InMemorySubstrate();
    var prodEuState =
        new SubstrateAgentStateStore(
            substrate, "prod-eu", Clock.systemUTC(), TestMappers.plainlyPinned());
    var backend =
        new SubstrateComputations(
            substrate,
            TestMappers.plainlyPinned(),
            Kinds.tool(AgentType.of("ops")),
            Kinds.outbox(AgentType.of("ops")));
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var intentStore =
        new SubstrateIntentStore<>(
            substrate, "prod-eu", OpsIntent.class, TestMappers.plainlyPinned());

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
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(firstAttempt, null)),
                List.of(new ModelEvent.ToolUseEmitted(declareCall, null)),
                List.of(new ModelEvent.ToolUseEmitted(retryAttempt, null)),
                List.of(new ModelEvent.TextChunk("Done — prod-eu restarted."))));

    var harness =
        Nessy.harness(
            h ->
                h.type("ops")
                    .model(provider)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(
                        ToolGrant.grant(
                            new IntentTool<>(OpsIntent.class, intentStore), UsagePolicy.allow()),
                        restartGrant(
                            intentStore, riskAssessor(Likelihood.HIGH, Impact.HIGH), false))
                    .substrate(substrate)
                    .backend(backend)
                    .approvalNotifier(requests::add)
                    .executor(pump));
    try {
      System.out.println("== the model asks to restart prod-eu with no declared intent ==");
      harness.bind(AgentId.of("prod-eu")).tell("please restart prod-eu");
      pump.pumpUntilQuiet();

      System.out.println("intent recorded: " + intentStore.latest());
      assertThat(intentStore.latest()).contains(new Restart("prod-eu", "stuck deploy"));

      System.out.println("== the desk approves the retried restart ==");
      assertThat(prodEuState.load().phase()).isInstanceOf(Phase.AwaitingTools.class);
      assertThat(requests).hasSize(1);
      ApprovalRequest request = requests.getFirst();
      assertThat(request.context().declaredIntent(OpsIntent.class))
          .contains(new Restart("prod-eu", "stuck deploy"));
      // The approval kind lives on Continuum now (continuum-adoption spec §3), not as a Substrate
      // document under Kinds.approval — the request's own id (Continuum-minted) is the handle.
      var computation = request.id();

      harness.approvals().approve(computation);
      pump.pumpUntilQuiet();

      // The grant arc (durable-deliveries spec §5a, Task 3): the delivery worker dispatches the
      // call past the gate directly from the grant's own continuation — no re-derivation, no
      // second ask. The tool runs exactly once and the turn completes.
      System.out.println("final phase: " + prodEuState.load().phase().getClass().getSimpleName());
      assertThat(prodEuState.load().phase()).isEqualTo(new Phase.Idle());
      assertThat(requests).hasSize(1);
    } finally {
      harness.shutdown();
    }
  }

  @Test
  void aDeclaredTargetThatDoesNotMatchTheAttemptedTargetIsDeniedNamingBoth() {
    var pump = new PumpedExecutor();
    var substrate = new InMemorySubstrate();
    var prodEuState =
        new SubstrateAgentStateStore(
            substrate, "prod-eu", Clock.systemUTC(), TestMappers.plainlyPinned());
    var backend =
        new SubstrateComputations(
            substrate,
            TestMappers.plainlyPinned(),
            Kinds.tool(AgentType.of("ops")),
            Kinds.outbox(AgentType.of("ops")));
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var intentStore =
        new SubstrateIntentStore<>(
            substrate, "prod-eu", OpsIntent.class, TestMappers.plainlyPinned());

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
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(declareCall, null)),
                List.of(new ModelEvent.ToolUseEmitted(mismatchedAttempt, null)),
                List.of(new ModelEvent.TextChunk("Understood — I will not restart prod-us."))));

    var harness =
        Nessy.harness(
            h ->
                h.type("ops")
                    .model(provider)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(
                        ToolGrant.grant(
                            new IntentTool<>(OpsIntent.class, intentStore), UsagePolicy.allow()),
                        restartGrant(intentStore, riskAssessor(Likelihood.HIGH, Impact.HIGH), true))
                    .substrate(substrate)
                    .backend(backend)
                    .approvalNotifier(requests::add)
                    .executor(pump));
    try {
      System.out.println("== the model declares prod-eu, then tries to restart prod-us instead ==");
      harness.bind(AgentId.of("prod-eu")).tell("please restart prod-us");
      pump.pumpUntilQuiet();

      assertThat(requests).isEmpty();
      System.out.println("final phase: " + prodEuState.load().phase().getClass().getSimpleName());
      assertThat(prodEuState.load().phase()).isEqualTo(new Phase.Idle());

      List<Message> transcript =
          new SubstrateMemory(substrate, "prod-eu", TestMappers.plainlyPinned())
              .recall()
              .messages();
      ToolResultBlock mismatchDenial = (ToolResultBlock) transcript.get(4).content().getFirst();
      System.out.println("mismatch denial: " + mismatchDenial);
      assertThat(mismatchDenial.isError()).isTrue();
      assertThat(mismatchDenial.content()).contains("prod-eu").contains("prod-us");
    } finally {
      harness.shutdown();
    }
  }

  @Test
  void anUnrepresentableDeclarationFailsInBandNamingTheLegalTypesAndStoresNothing() {
    var pump = new PumpedExecutor();
    var substrate = new InMemorySubstrate();
    var prodEuState =
        new SubstrateAgentStateStore(
            substrate, "prod-eu", Clock.systemUTC(), TestMappers.plainlyPinned());
    var backend =
        new SubstrateComputations(
            substrate,
            TestMappers.plainlyPinned(),
            Kinds.tool(AgentType.of("ops")),
            Kinds.outbox(AgentType.of("ops")));
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var intentStore =
        new SubstrateIntentStore<>(
            substrate, "prod-eu", OpsIntent.class, TestMappers.plainlyPinned());

    var unrepresentableDeclare =
        new ToolCall(
            "c1",
            "declare-intent",
            JsonNodeFactory.instance.objectNode().put("type", "Nuke").put("target", "x"));
    var provider =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(unrepresentableDeclare, null)),
                List.of(new ModelEvent.TextChunk("Understood — that shape is not supported."))));

    var harness =
        Nessy.harness(
            h ->
                h.type("ops")
                    .model(provider)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(
                        ToolGrant.grant(
                            new IntentTool<>(OpsIntent.class, intentStore), UsagePolicy.allow()),
                        restartGrant(
                            intentStore, riskAssessor(Likelihood.HIGH, Impact.HIGH), false))
                    .substrate(substrate)
                    .backend(backend)
                    .approvalNotifier(requests::add)
                    .executor(pump));
    try {
      System.out.println("== the model declares a shape outside the vocabulary ==");
      harness.bind(AgentId.of("prod-eu")).tell("please nuke x");
      pump.pumpUntilQuiet();

      System.out.println("intent recorded: " + intentStore.latest());
      assertThat(intentStore.latest()).isEmpty();

      System.out.println("final phase: " + prodEuState.load().phase().getClass().getSimpleName());
      assertThat(prodEuState.load().phase()).isEqualTo(new Phase.Idle());

      List<Message> transcript =
          new SubstrateMemory(substrate, "prod-eu", TestMappers.plainlyPinned())
              .recall()
              .messages();
      ToolResultBlock bindingError = (ToolResultBlock) transcript.get(2).content().getFirst();
      System.out.println("binding error: " + bindingError);
      assertThat(bindingError.isError()).isTrue();
      assertThat(bindingError.content()).contains("Restart").contains("Diagnose");
    } finally {
      harness.shutdown();
    }
  }
}
