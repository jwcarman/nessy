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
package org.jwcarman.nessy.api.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationReport;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.tool.authorization.GrantStory;
import org.jwcarman.nessy.api.tool.authorization.Impact;
import org.jwcarman.nessy.api.tool.authorization.Likelihood;
import org.jwcarman.nessy.api.tool.authorization.RiskAssessment;
import org.jwcarman.nessy.api.tool.authorization.RiskLevel;
import org.jwcarman.nessy.api.tool.authorization.RiskPolicies;

class UsagePolicyTest {

  private static ToolCall spendCall(int amount) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.set("amount", IntNode.valueOf(amount));
    return new ToolCall("c1", "spend", args);
  }

  private static AuthzContext contextFor(ToolCall call) {
    return AuthzContext.of("test-agent", call);
  }

  @Nested
  class Factories {

    @Test
    void allowAlwaysAllows() {
      UsagePolicy policy = UsagePolicy.allow();
      ToolCall call = spendCall(1);

      assertThat(policy.evaluate(contextFor(call))).isEqualTo(new PolicyDecision.Allow());
    }

    @Test
    void denyAlwaysDeniesWithTheSameReason() {
      UsagePolicy policy = UsagePolicy.deny("no budget");
      ToolCall first = spendCall(1);
      ToolCall second = spendCall(999);

      assertThat(policy.evaluate(contextFor(first)))
          .isEqualTo(new PolicyDecision.Deny("no budget"));
      assertThat(policy.evaluate(contextFor(second)))
          .isEqualTo(new PolicyDecision.Deny("no budget"));
    }

    @Test
    void requireApprovalAlwaysDefers() {
      UsagePolicy policy = UsagePolicy.requireApproval();
      ToolCall call = spendCall(1);

      assertThat(policy.evaluate(contextFor(call))).isEqualTo(new PolicyDecision.RequireApproval());
    }

    @Test
    void allowReturnsTheSameInstanceEveryTime() {
      assertThat(UsagePolicy.allow()).isSameAs(UsagePolicy.allow());
    }
  }

  @Nested
  class Contextual_policies {

    /**
     * A rung-1 policy that behaves like a spend cap: allow under the limit, deny at or over it —
     * reading the call out of {@link AuthzContext#call()} rather than a raw {@code ToolCall}
     * parameter (design of record 2026-08-16-authorization §5's migration: today's two-arg policies
     * become context-reading lambdas).
     */
    private static UsagePolicy approveUnder(int limit) {
      return UsagePolicy.of(
          context -> {
            int amount = context.call().arguments().get("amount").asInt();
            return amount < limit
                ? new PolicyDecision.Allow()
                : new PolicyDecision.Deny("amount " + amount + " exceeds limit " + limit);
          });
    }

    @Test
    void aCallUnderTheLimitIsAllowed() {
      UsagePolicy policy = approveUnder(100);
      ToolCall call = spendCall(50);

      assertThat(policy.evaluate(contextFor(call))).isEqualTo(new PolicyDecision.Allow());
    }

    @Test
    void aCallAtOrOverTheLimitIsDenied() {
      UsagePolicy policy = approveUnder(100);
      ToolCall call = spendCall(100);

      assertThat(policy.evaluate(contextFor(call)))
          .isEqualTo(new PolicyDecision.Deny("amount 100 exceeds limit 100"));
    }
  }

  /**
   * A tool carries no authority of its own — {@code Tool#requiresApproval()} is gone, and {@link
   * ToolGrant#grant(Tool, UsagePolicy)} is the sole construction path. There is no derived floor
   * left to test: {@code a_grant_states_its_policy_or_does_not_compile} is a compile-level property
   * (the single-arg {@code grant(tool)} no longer exists as a method to call), so what remains to
   * pin here is the static factory's own validation.
   */
  @Nested
  class Grant_construction {

    private static final class Recorder implements Tool<Object> {
      @Override
      public String name() {
        return "recorder";
      }

      @Override
      public String description() {
        return "Records calls";
      }

      @Override
      public Class<Object> inputType() {
        return Object.class;
      }

      @Override
      public Awaited<ToolResult> execute(Object input, ToolContext context) {
        return Awaited.ready(ToolResult.ok("recorded"));
      }
    }

    @Test
    void grantRejectsANullTool() {
      UsagePolicy policy = UsagePolicy.allow();

      assertThatThrownBy(() -> ToolGrant.grant(null, policy))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("tool");
    }

    @Test
    void grantRejectsANullPolicy() {
      Recorder tool = new Recorder();

      assertThatThrownBy(() -> ToolGrant.grant(tool, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("policy");
    }

    @Test
    void grantStatesTheToolAndPolicyItWasGiven() {
      Recorder tool = new Recorder();
      UsagePolicy policy = UsagePolicy.requireApproval();

      ToolGrant grant = ToolGrant.grant(tool, policy);

      assertThat(grant.tool()).isSameAs(tool);
      assertThat(grant.policy()).isSameAs(policy);
    }
  }

  /**
   * {@code ToolGrant} is a final class with a private constructor (action-wave spec §8) — the
   * {@code grant(...)} factories, exercised in {@link Grant_construction}, are the only supported
   * door, so there is no raw constructor left to pin here. What remains is {@link PolicyDecision}'s
   * own validation.
   */
  @Nested
  class Validation {

    @Test
    void aDenyDecisionRejectsABlankReason() {
      assertThatThrownBy(() -> new PolicyDecision.Deny(" "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("reason");
    }

    @Test
    void aDenyDecisionRejectsANullReason() {
      assertThatThrownBy(() -> new PolicyDecision.Deny(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("reason");
    }
  }

  @Nested
  class All_of_composition {

    @Test
    void allAllowIsAllowed() {
      UsagePolicy policy = UsagePolicy.allOf(List.of(UsagePolicy.allow(), UsagePolicy.allow()));
      ToolCall call = spendCall(1);

      assertThat(policy.evaluate(contextFor(call))).isEqualTo(new PolicyDecision.Allow());
    }

    @Test
    void theFirstDenyWinsAndItsOwnReasonSurfaces() {
      UsagePolicy policy =
          UsagePolicy.allOf(
              List.of(UsagePolicy.allow(), UsagePolicy.deny("first"), UsagePolicy.deny("second")));
      ToolCall call = spendCall(1);

      assertThat(policy.evaluate(contextFor(call))).isEqualTo(new PolicyDecision.Deny("first"));
    }

    @Test
    void aRequireApprovalWinsOverAnAllowWhenNoDenyIsPresent() {
      UsagePolicy policy =
          UsagePolicy.allOf(List.of(UsagePolicy.allow(), UsagePolicy.requireApproval()));
      ToolCall call = spendCall(1);

      assertThat(policy.evaluate(contextFor(call))).isEqualTo(new PolicyDecision.RequireApproval());
    }

    @Test
    void evaluatesInOrderSoALaterDenyNeverOverridesAnEarlierOne() {
      UsagePolicy policy =
          UsagePolicy.allOf(List.of(UsagePolicy.deny("early"), UsagePolicy.requireApproval()));
      ToolCall call = spendCall(1);

      assertThat(policy.evaluate(contextFor(call))).isEqualTo(new PolicyDecision.Deny("early"));
    }

    @Test
    void rejectsAnEmptyList() {
      List<UsagePolicy> empty = List.of();

      assertThatThrownBy(() -> UsagePolicy.allOf(empty))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAListContainingANullElement() {
      List<UsagePolicy> withNull = Arrays.asList(UsagePolicy.allow(), null);

      assertThatThrownBy(() -> UsagePolicy.allOf(withNull))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCompositeIsNeverStatic() {
      UsagePolicy policy = UsagePolicy.allOf(List.of(UsagePolicy.allow(), UsagePolicy.allow()));

      assertThat(policy).isNotInstanceOf(UsagePolicy.Static.class);
    }

    private record Restart(String target) {}

    /**
     * A standin for {@code IntentPolicies.requireDeclared(Restart.class)} — that factory now lives
     * in {@code nessy-intent} (substrate spec §11), which this module may not depend on. What these
     * tests exercise is {@link UsagePolicy#allOf(List)}'s own ordering and short-circuiting, not
     * the intent feature itself, so a minimal local policy with the same deny/allow shape stands in
     * for it here.
     */
    private static UsagePolicy requireDeclaredRestart() {
      return UsagePolicy.of(
          context ->
              context
                  .declaredIntent(Restart.class)
                  .<PolicyDecision>map(declared -> new PolicyDecision.Allow())
                  .orElseGet(
                      () ->
                          new PolicyDecision.Deny(
                              "no Restart declared — declare your intent with the declare-intent"
                                  + " tool before acting")));
    }

    @Test
    void combinesRequireDeclaredWithARiskThresholdPolicyDenyingOnTheUndeclaredIntentFirst() {
      UsagePolicy policy =
          UsagePolicy.allOf(
              List.of(
                  requireDeclaredRestart(), RiskPolicies.threshold(RiskLevel.LOW, RiskLevel.HIGH)));
      ToolCall call = spendCall(1);

      PolicyDecision decision = policy.evaluate(contextFor(call));

      assertThat(decision).isInstanceOf(PolicyDecision.Deny.class);
      assertThat(((PolicyDecision.Deny) decision).reason()).contains("declare-intent");
    }

    @Test
    void combinesRequireDeclaredWithARiskThresholdPolicyDenyingOnRiskWhenIntentIsDeclared() {
      UsagePolicy policy =
          UsagePolicy.allOf(
              List.of(
                  requireDeclaredRestart(), RiskPolicies.threshold(RiskLevel.LOW, RiskLevel.HIGH)));
      ToolCall call = spendCall(1);
      RiskAssessment highRisk =
          new RiskAssessment(Likelihood.MODERATE, Impact.MODERATE, RiskLevel.HIGH, Set.of());
      AuthzContext context =
          contextFor(call)
              .with(AuthzContext.DECLARED_INTENT_KEY, new Restart("prod-eu"))
              .with(AuthzContext.RISK_KEY, highRisk);

      PolicyDecision decision = policy.evaluate(context);

      assertThat(decision).isInstanceOf(PolicyDecision.Deny.class);
      assertThat(((PolicyDecision.Deny) decision).reason()).contains("HIGH");
    }

    @Test
    void combinesRequireDeclaredWithARiskThresholdPolicyAllowingWhenBothAreSatisfied() {
      UsagePolicy policy =
          UsagePolicy.allOf(
              List.of(
                  requireDeclaredRestart(), RiskPolicies.threshold(RiskLevel.LOW, RiskLevel.HIGH)));
      ToolCall call = spendCall(1);
      RiskAssessment lowRisk =
          new RiskAssessment(Likelihood.MODERATE, Impact.MODERATE, RiskLevel.VERY_LOW, Set.of());
      AuthzContext context =
          contextFor(call)
              .with(AuthzContext.DECLARED_INTENT_KEY, new Restart("prod-eu"))
              .with(AuthzContext.RISK_KEY, lowRisk);

      assertThat(policy.evaluate(context)).isEqualTo(new PolicyDecision.Allow());
    }

    @Test
    void namesItsOwnClassForTheAuthorizationReport() {
      UsagePolicy policy = UsagePolicy.allOf(List.of(UsagePolicy.allow(), UsagePolicy.allow()));
      Grant_construction.Recorder tool = new Grant_construction.Recorder();

      ToolGrant grant = ToolGrant.grant(tool, policy);

      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.policy()).isEqualTo("AllOfPolicy");
    }
  }
}
