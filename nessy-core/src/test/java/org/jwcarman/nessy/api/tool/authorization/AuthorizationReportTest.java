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
import org.jwcarman.nessy.api.tool.EffectfulTool;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;

/**
 * Pins {@link AuthorizationReport} against known wiring (design of record 2026-08-16-authorization
 * §8): a rung-0 grant, whose story must honestly say it renders no effect and runs no enrichers,
 * and a fully-wired rung-3 grant, whose story must name its typed effect and its enrichers in
 * order.
 */
class AuthorizationReportTest {

  record ClockInput() {}

  /** An untyped rung-0/1 tool — no effect statement worth naming, only {@code toString}. */
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

  record TransferEffect(String accountId, int cents) {}

  /** A typed tool whose effect a rung-3 grant welds through enrichers to a named policy. */
  static final class TransferTool implements EffectfulTool<TransferInput, TransferEffect> {

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
    public TransferEffect effect(TransferInput input) {
      throw new AssertionError("building a report must never render an effect");
    }

    @Override
    public Awaited<ToolResult> execute(TransferInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("transferred"));
    }
  }

  /** A named policy class — its report identity is its own simple name, no field to declare. */
  static final class RiskThresholdPolicy implements UsagePolicy<TransferEffect> {

    @Override
    public PolicyDecision evaluate(AuthzContext context, TransferEffect effect) {
      throw new AssertionError("building a report must never evaluate a policy");
    }
  }

  private static Enricher<TransferEffect> explodingEnricher(String failureMessage) {
    return (context, effect) -> {
      throw new AssertionError(failureMessage);
    };
  }

  @Nested
  class A_rung_0_grant {

    private final ToolGrant grant = ToolGrant.grant(new ClockTool(), UsagePolicy.allow());

    @Test
    void reports_no_effect_rendering_and_no_enrichers() {
      AuthorizationReport report = AuthorizationReport.of(List.of(grant));

      GrantStory story = report.grants().getFirst();

      assertThat(story.toolName()).isEqualTo("clock");
      assertThat(story.effectRendered()).isFalse();
      assertThat(story.effectType()).isEmpty();
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
              List.of(explodingEnricher("a static policy's grant must never run an enricher")),
              UsagePolicy.allow());

      GrantStory story = AuthorizationReport.of(List.of(staticWithEnrichers)).grants().getFirst();

      assertThat(story.effectRendered()).isFalse();
      assertThat(story.enrichers()).isEmpty();
    }
  }

  @Nested
  class A_fully_wired_rung_3_grant {

    private final ToolGrant grant =
        ToolGrant.grant(
            new TransferTool(),
            List.of(
                Enricher.named("principal", (context, effect) -> context),
                (context, effect) -> context),
            new RiskThresholdPolicy());

    @Test
    void names_its_typed_effect() {
      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.toolName()).isEqualTo("transfer");
      assertThat(story.effectRendered()).isTrue();
      assertThat(story.effectType()).contains("TransferEffect");
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
    void renders_the_whole_story_as_one_arrow_chain() {
      AuthorizationReport report = AuthorizationReport.of(List.of(grant));

      assertThat(report.render())
          .isEqualTo(
              "transfer:  TransferEffect → principal → enricher 2 → policy (RiskThresholdPolicy)");
    }
  }

  @Nested
  class Aggregation {

    @Test
    void orders_grants_by_tool_name_regardless_of_wiring_order() {
      ToolGrant transfer =
          ToolGrant.grant(new TransferTool(), List.of(), new RiskThresholdPolicy());
      ToolGrant clock = ToolGrant.grant(new ClockTool(), UsagePolicy.allow());

      AuthorizationReport report = AuthorizationReport.of(List.of(transfer, clock));

      assertThat(report.grants())
          .extracting(GrantStory::toolName)
          .containsExactly("clock", "transfer");
    }

    @Test
    void render_joins_every_story_one_per_line() {
      ToolGrant transfer =
          ToolGrant.grant(new TransferTool(), List.of(), new RiskThresholdPolicy());
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
    void require_approval_still_renders_an_effect_since_it_is_not_a_static_verdict() {
      ToolGrant grant = ToolGrant.grant(new ClockTool(), UsagePolicy.requireApproval());

      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.effectRendered()).isTrue();
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
      Enricher<Object> bare = (context, effect) -> context;

      assertThat(bare.displayName()).isEmpty();
    }

    @Test
    void named_wraps_a_delegate_without_changing_its_behavior() {
      Key<String> seen = new Key<>(String.class, "seen");
      ToolCall call = new ToolCall("c1", "clock", JsonNodeFactory.instance.objectNode());
      AuthzContext context = AuthzContext.of("test-agent", call);
      Enricher<Object> delegate = (ctx, effect) -> ctx.with(seen, "yes");
      Enricher<Object> named = Enricher.named("marker", delegate);

      AuthzContext extended = named.enrich(context, "irrelevant");

      assertThat(named.displayName()).contains("marker");
      assertThat(extended.get(seen)).contains("yes");
    }
  }
}
