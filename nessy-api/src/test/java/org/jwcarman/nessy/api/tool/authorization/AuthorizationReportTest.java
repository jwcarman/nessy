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
package org.jwcarman.nessy.api.tool.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;

/**
 * Pins {@link AuthorizationReport} against known wiring (design of record 2026-08-16-authorization
 * §8, amended by action-wave spec §1): a rung-0 grant, whose story must honestly say it renders no
 * action and runs no enrichers, and a fully-wired rung-3 grant, whose story must name its
 * contributor's display name and its enrichers in order.
 */
class AuthorizationReportTest {

  record ClockInput() {}

  /** An untyped rung-0/1 tool — no action statement worth naming, only the default contributor. */
  static final class ClockTool implements Tool<ClockInput> {

    @Override
    public String name() {
      return "clock";
    }

    @Override
    public String description() {
      return "Reads the current time";
    }

    @Override
    public Class<ClockInput> inputType() {
      return ClockInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(ClockInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("noon"));
    }
  }

  record TransferInput(String accountId, int cents) {}

  record TransferAction(String accountId, int cents) {}

  /** A typed tool whose action a rung-3 grant welds through enrichers to a named policy. */
  static final class TransferTool implements Tool<TransferInput> {

    @Override
    public String name() {
      return "transfer";
    }

    @Override
    public String description() {
      return "Moves money between accounts";
    }

    @Override
    public Class<TransferInput> inputType() {
      return TransferInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(TransferInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("transferred"));
    }
  }

  private static final ActionContributor<TransferInput, TransferAction> TRANSFER_ACTION =
      input -> {
        throw new AssertionError("building a report must never render an action");
      };

  private static final ActionContributor<TransferInput, TransferAction> NAMED_TRANSFER_ACTION =
      ActionContributor.named("transfer-action", TRANSFER_ACTION);

  /** A named policy class — its report identity is its own simple name, no field to declare. */
  static final class RiskThresholdPolicy implements UsagePolicy<TransferAction> {

    @Override
    public PolicyDecision evaluate(AuthzContext context, TransferAction action) {
      throw new AssertionError("building a report must never evaluate a policy");
    }
  }

  private static Enricher<TransferAction> explodingEnricher(String failureMessage) {
    return (context, action) -> {
      throw new AssertionError(failureMessage);
    };
  }

  @Nested
  class A_rung_0_grant {

    private final ToolGrant grant = ToolGrant.grant(new ClockTool(), UsagePolicy.allow());

    @Test
    void reports_no_action_rendering_and_no_enrichers() {
      AuthorizationReport report = AuthorizationReport.of(List.of(grant));

      GrantStory story = report.grants().getFirst();

      assertThat(story.toolName()).isEqualTo("clock");
      assertThat(story.actionRendered()).isFalse();
      assertThat(story.actionContributor()).isEmpty();
      assertThat(story.enrichers()).isEmpty();
      assertThat(story.policy()).isEqualTo("allow()");
    }

    @Test
    void renders_as_the_tool_name_and_the_policy_alone() {
      AuthorizationReport report = AuthorizationReport.of(List.of(grant));

      assertThat(report.render()).isEqualTo("clock: allow()");
    }

    @Test
    void
        treats_enrichers_the_grant_happens_to_carry_as_dead_wiring_since_the_ladder_law_skips_them() {
      ToolGrant staticWithEnrichers =
          ToolGrant.grant(
              new TransferTool(),
              TRANSFER_ACTION,
              List.of(explodingEnricher("a static policy's grant must never run an enricher")),
              UsagePolicy.allow());

      GrantStory story = AuthorizationReport.of(List.of(staticWithEnrichers)).grants().getFirst();

      assertThat(story.actionRendered()).isFalse();
      assertThat(story.enrichers()).isEmpty();
    }
  }

  @Nested
  class A_fully_wired_rung_3_grant {

    private final ToolGrant grant =
        ToolGrant.grant(
            new TransferTool(),
            NAMED_TRANSFER_ACTION,
            List.of(
                Enricher.named("principal", (context, action) -> context),
                (context, action) -> context),
            new RiskThresholdPolicy());

    @Test
    void names_its_action_contributor() {
      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.toolName()).isEqualTo("transfer");
      assertThat(story.actionRendered()).isTrue();
      assertThat(story.actionContributor()).contains("transfer-action");
    }

    @Test
    void
        names_its_enrichers_in_order_falling_back_to_a_position_when_one_carries_no_display_name() {
      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.enrichers()).containsExactly("principal", "enricher 2");
    }

    @Test
    void names_its_policy_by_its_own_class() {
      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.policy()).isEqualTo("RiskThresholdPolicy");
    }

    @Test
    void names_the_real_risk_policies_threshold_policy_by_its_own_class_too() {
      ToolGrant thresholdGrant =
          ToolGrant.grant(
              new TransferTool(),
              NAMED_TRANSFER_ACTION,
              RiskPolicies.threshold(RiskLevel.LOW, RiskLevel.HIGH));

      GrantStory story = AuthorizationReport.of(List.of(thresholdGrant)).grants().getFirst();

      assertThat(story.policy()).isEqualTo("ThresholdPolicy");
    }

    @Test
    void renders_the_whole_story_as_one_arrow_chain() {
      AuthorizationReport report = AuthorizationReport.of(List.of(grant));

      assertThat(report.render())
          .isEqualTo(
              "transfer: action(transfer-action) → principal → enricher 2 → policy"
                  + " (RiskThresholdPolicy)");
    }
  }

  @Nested
  class An_anonymous_contributor {

    @Test
    void reports_unnamed_when_a_custom_contributor_carries_no_display_name() {
      ToolGrant grant =
          ToolGrant.grant(new TransferTool(), TRANSFER_ACTION, new RiskThresholdPolicy());

      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.actionContributor()).isEmpty();
      assertThat(story.render()).startsWith("transfer: action(unnamed)");
    }
  }

  @Nested
  class The_untyped_doors_default_contributor {

    @Test
    void reports_its_own_string_value_of_display_name_not_the_unnamed_placeholder() {
      ToolGrant grant =
          ToolGrant.grant(
              new ClockTool(), UsagePolicy.of((context, action) -> new PolicyDecision.Allow()));

      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.actionContributor()).contains("String.valueOf");
      assertThat(story.render()).startsWith("clock: action(String.valueOf)");
    }
  }

  @Nested
  class Aggregation {

    @Test
    void orders_grants_by_tool_name_regardless_of_wiring_order() {
      ToolGrant transfer =
          ToolGrant.grant(
              new TransferTool(), TRANSFER_ACTION, List.of(), new RiskThresholdPolicy());
      ToolGrant clock = ToolGrant.grant(new ClockTool(), UsagePolicy.allow());

      AuthorizationReport report = AuthorizationReport.of(List.of(transfer, clock));

      assertThat(report.grants())
          .extracting(GrantStory::toolName)
          .containsExactly("clock", "transfer");
    }

    @Test
    void render_joins_every_story_one_per_line() {
      ToolGrant transfer =
          ToolGrant.grant(
              new TransferTool(), TRANSFER_ACTION, List.of(), new RiskThresholdPolicy());
      ToolGrant clock = ToolGrant.grant(new ClockTool(), UsagePolicy.allow());

      AuthorizationReport report = AuthorizationReport.of(List.of(transfer, clock));

      assertThat(report.render().lines()).hasSize(2);
    }
  }

  @Nested
  class The_deny_and_require_approval_statics {

    @Test
    void deny_reports_its_own_reason() {
      ToolGrant grant = ToolGrant.grant(new ClockTool(), UsagePolicy.deny("no clocks today"));

      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.policy()).isEqualTo("deny(\"no clocks today\")");
    }

    @Test
    void require_approval_still_renders_an_action_since_it_is_not_a_static_verdict() {
      ToolGrant grant = ToolGrant.grant(new ClockTool(), UsagePolicy.requireApproval());

      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.actionRendered()).isTrue();
    }

    @Test
    void require_approval_names_itself_by_its_own_canonical_factory_not_a_synthetic_lambda_token() {
      ToolGrant grant = ToolGrant.grant(new ClockTool(), UsagePolicy.requireApproval());

      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.policy()).isEqualTo("requireApproval()");
    }
  }

  @Nested
  class Optional_display_name_default {

    @Test
    void a_bare_enricher_lambda_carries_no_display_name_by_default() {
      Enricher<Object> bare = (context, action) -> context;

      assertThat(bare.displayName()).isEmpty();
    }

    @Test
    void named_wraps_a_delegate_without_changing_its_behavior() {
      Key<String> seen = new Key<>(String.class, "seen");
      ToolCall call = new ToolCall("c1", "clock", JsonNodeFactory.instance.objectNode());
      AuthzContext context = AuthzContext.of("test-agent", call);
      Enricher<Object> delegate = (ctx, action) -> ctx.with(seen, "yes");
      Enricher<Object> named = Enricher.named("marker", delegate);

      AuthzContext extended = named.enrich(context, "irrelevant");

      assertThat(named.displayName()).contains("marker");
      assertThat(extended.get(seen)).contains("yes");
    }
  }
}
