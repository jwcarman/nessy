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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalContext;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.api.tool.approval.Approvers;

/**
 * Pins {@link AuthorizationReport} against known wiring (design of record 2026-08-16-authorization
 * §8, amended by the approval-lifecycle spec §1.4): a rung-0 grant, whose story must honestly say
 * it renders no action and runs no enrichers, and a fully-wired rung-3 grant, whose story must name
 * its contributor's display name and its enrichers in order.
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

  /** A typed tool whose action a rung-3 grant welds through enrichers to a named approver. */
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

  /** A named approver class — its report identity is its own simple name, no field to declare. */
  static final class RiskThresholdApprover implements Approver {

    @Override
    public ApprovalOutcome approve(ApprovalContext context) {
      throw new AssertionError("building a report must never consult an approver");
    }
  }

  private static Enricher explodingEnricher(String failureMessage) {
    return draft -> {
      throw new AssertionError(failureMessage);
    };
  }

  private static Enricher inertEnricher() {
    return draft -> {
      // a report reads wiring; nothing to deposit
    };
  }

  @Nested
  class A_rung_0_grant {

    private final ToolGrant grant = ToolGrant.grant(new ClockTool(), Approvers.allow());

    @Test
    void reports_no_action_rendering_and_no_enrichers() {
      AuthorizationReport report = AuthorizationReport.of(List.of(grant));

      GrantStory story = report.grants().getFirst();

      assertThat(story.toolName()).isEqualTo("clock");
      assertThat(story.actionRendered()).isFalse();
      assertThat(story.actionContributor()).isEmpty();
      assertThat(story.enrichers()).isEmpty();
      assertThat(story.approver()).isEqualTo("allow()");
    }

    @Test
    void renders_as_the_tool_name_and_the_approver_alone() {
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
              List.of(explodingEnricher("a static approver's grant must never run an enricher")),
              Approvers.allow());

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
            List.of(Enricher.named("principal", inertEnricher()), inertEnricher()),
            new RiskThresholdApprover());

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
    void names_its_approver_by_its_own_class() {
      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.approver()).isEqualTo("RiskThresholdApprover");
    }

    @Test
    void renders_the_whole_story_as_one_arrow_chain() {
      AuthorizationReport report = AuthorizationReport.of(List.of(grant));

      assertThat(report.render())
          .isEqualTo(
              "transfer: action(transfer-action) → principal → enricher 2 → approver"
                  + " (RiskThresholdApprover)");
    }
  }

  @Nested
  class An_anonymous_contributor {

    @Test
    void reports_unnamed_when_a_custom_contributor_carries_no_display_name() {
      ToolGrant grant =
          ToolGrant.grant(new TransferTool(), TRANSFER_ACTION, new RiskThresholdApprover());

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
              new ClockTool(), context -> new ApprovalOutcome.Answered(Approval.approved()));

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
              new TransferTool(), TRANSFER_ACTION, List.of(), new RiskThresholdApprover());
      ToolGrant clock = ToolGrant.grant(new ClockTool(), Approvers.allow());

      AuthorizationReport report = AuthorizationReport.of(List.of(transfer, clock));

      assertThat(report.grants())
          .extracting(GrantStory::toolName)
          .containsExactly("clock", "transfer");
    }

    @Test
    void render_joins_every_story_one_per_line() {
      ToolGrant transfer =
          ToolGrant.grant(
              new TransferTool(), TRANSFER_ACTION, List.of(), new RiskThresholdApprover());
      ToolGrant clock = ToolGrant.grant(new ClockTool(), Approvers.allow());

      AuthorizationReport report = AuthorizationReport.of(List.of(transfer, clock));

      assertThat(report.render().lines()).hasSize(2);
    }
  }

  @Nested
  class The_deny_static_and_the_deferring_approver {

    @Test
    void deny_reports_its_own_reason() {
      ToolGrant grant = ToolGrant.grant(new ClockTool(), Approvers.deny("no clocks today"));

      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.approver()).isEqualTo("deny(\"no clocks today\")");
    }

    @Test
    void defer_still_renders_an_action_since_it_is_not_a_static_answer() {
      ToolGrant grant = ToolGrant.grant(new ClockTool(), Approvers.defer());

      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.actionRendered()).isTrue();
    }

    @Test
    void defer_summarises_by_whatever_identity_its_lambda_class_carries() {
      ToolGrant grant = ToolGrant.grant(new ClockTool(), Approvers.defer());

      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.approver()).isNotBlank();
    }
  }

  @Nested
  class Optional_display_name_default {

    @Test
    void a_bare_enricher_lambda_carries_no_display_name_by_default() {
      Enricher bare = inertEnricher();

      assertThat(bare.displayName()).isEmpty();
    }

    @Test
    void named_wraps_a_delegate_without_changing_its_behavior() {
      Key<String> seen = new Key<>(String.class, "seen");
      ToolCall call = new ToolCall("c1", "clock", JsonNodeFactory.instance.objectNode());
      ApprovalRequest.Draft draft =
          ApprovalRequest.draft("test-agent", "scope-1", call, new ObjectMapper());
      Enricher named = Enricher.named("marker", d -> d.deposit(seen, "yes"));

      named.enrich(draft);

      assertThat(named.displayName()).contains("marker");
      assertThat(draft.freeze().facts().get(seen)).contains("yes");
    }
  }
}
