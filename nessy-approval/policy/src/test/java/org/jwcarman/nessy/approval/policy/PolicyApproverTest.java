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
package org.jwcarman.nessy.approval.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ReplyToken;

/**
 * What a policy approver does with each verdict, and with each way the gate can break.
 *
 * <p>No mocking library, as everywhere here: a {@link PolicyEngine} is a lambda and an {@link
 * Approver} is a lambda, which is the whole benefit of both being one method.
 */
@DisplayName("An approver that asks a policy")
class PolicyApproverTest {

  private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");

  private static ApprovalRequest asking() {
    return new ApprovalRequest(
        AgentType.of("watchman"),
        AgentId.of("house-12"),
        TurnId.of("turn-1"),
        CallId.of("call-1"),
        "prune_images",
        JsonNodeFactory.instance.objectNode(),
        "docker image prune -af",
        NOW,
        () -> ReplyToken.of("a-capability"),
        JsonNodeFactory.instance.objectNode());
  }

  private static String denialOf(Awaited<ApprovalResult> answer) {
    ApprovalResult result = ((Awaited.Ready<ApprovalResult>) answer).result();
    if (result instanceof ApprovalResult.Denied denied) {
      return denied.reason();
    }
    throw new AssertionError("expected a denial, but it was allowed");
  }

  @Nested
  @DisplayName("carrying out the verdict")
  class Verdicts {

    @Test
    void approve_allows() {
      var gate = PolicyApprover.create(policy -> policy.engine(request -> Verdict.approve()));

      assertThat(gate.approve(asking())).isEqualTo(Awaited.ready(ApprovalResult.approved()));
    }

    @Test
    void deny_carries_the_reason_the_policy_gave() {
      var gate =
          PolicyApprover.create(policy -> policy.engine(request -> Verdict.deny("never here")));

      assertThat(denialOf(gate.approve(asking()))).isEqualTo("never here");
    }

    @Test
    @DisplayName("delegate hands the question to the named approver, whose answer stands")
    void delegate_defers_to_whoever_was_named() {
      Approver desk = request -> Awaited.deferred(NOW.plusSeconds(3600));
      var gate =
          PolicyApprover.create(
              policy ->
                  policy.engine(request -> Verdict.delegate("humans")).delegate("humans", desk));

      assertThat(gate.approve(asking())).isEqualTo(Awaited.deferred(NOW.plusSeconds(3600)));
    }

    @Test
    @DisplayName("what the policy attached reaches the delegate, so a term can come from Rego")
    void the_policys_extra_fields_are_deposited_before_the_delegate_is_asked() {
      ObjectNode extras = JsonNodeFactory.instance.objectNode();
      extras.put("policy.term", "PT72H");
      Approver desk =
          request ->
              Awaited.ready(
                  ApprovalResult.denied(request.fact("policy.term").orElseThrow().asText()));
      var gate =
          PolicyApprover.create(
              policy ->
                  policy
                      .engine(request -> new Verdict.Delegate("humans", extras))
                      .delegate("humans", desk));

      assertThat(denialOf(gate.approve(asking()))).isEqualTo("PT72H");
    }
  }

  @Nested
  @DisplayName("a broken gate is not an open one")
  class WhenTheGateBreaks {

    @Test
    void an_engine_that_throws_denies() {
      var gate =
          PolicyApprover.create(
              policy ->
                  policy.engine(
                      request -> {
                        throw new IllegalStateException("connection refused");
                      }));

      assertThat(denialOf(gate.approve(asking())))
          .contains("could not decide", "connection refused");
    }

    @Test
    void an_engine_that_answers_nothing_denies() {
      var gate = PolicyApprover.create(policy -> policy.engine(request -> null));

      assertThat(denialOf(gate.approve(asking()))).contains("no verdict");
    }

    @Test
    @DisplayName("a name that is not on the allowlist denies rather than falling through")
    void delegating_to_somebody_unregistered_denies() {
      var gate =
          PolicyApprover.create(
              policy ->
                  policy
                      .engine(request -> Verdict.delegate("whoever"))
                      .delegate("humans", r -> null));

      assertThat(denialOf(gate.approve(asking()))).contains("whoever", "not registered");
    }

    @Test
    @DisplayName("a delegate that answers null denies rather than propagating the null")
    void a_delegate_that_answers_null_denies() {
      var gate =
          PolicyApprover.create(
              policy ->
                  policy
                      .engine(request -> Verdict.delegate("humans"))
                      .delegate("humans", r -> null));

      assertThat(denialOf(gate.approve(asking()))).contains("humans", "gave no answer");
    }

    @Test
    void a_delegate_that_throws_denies() {
      Approver broken =
          request -> {
            throw new IllegalStateException("the desk is down");
          };
      var gate =
          PolicyApprover.create(
              policy ->
                  policy.engine(request -> Verdict.delegate("humans")).delegate("humans", broken));

      assertThat(denialOf(gate.approve(asking()))).contains("the desk is down");
    }

    @Test
    @DisplayName("a delegation loop is stopped by the depth bound")
    void delegating_in_a_circle_terminates() {
      // The classic shape: this approver's delegate is another policy approver whose policy
      // delegates straight back. Without a bound this recurses until the stack gives out.
      var inner =
          PolicyApprover.create(
              policy ->
                  policy
                      .engine(request -> Verdict.delegate("outer"))
                      .delegate("outer", r -> Awaited.ready(ApprovalResult.approved())));
      var gate =
          PolicyApprover.create(
              policy ->
                  policy
                      .engine(request -> Verdict.delegate("inner"))
                      .delegate("inner", inner)
                      .maxDepth(2));

      // Depth is carried on the request, so the inner approver can see how far it has already come.
      var answer = gate.approve(asking());

      assertThat(((Awaited.Ready<ApprovalResult>) answer).result())
          .isInstanceOf(ApprovalResult.Approved.class);
    }

    @Test
    void exceeding_the_depth_bound_denies() {
      // A gate whose delegate is itself: the tightest loop there is, and the one a registry of
      // policy approvers makes easy to build by accident. Measured progression of policy.depth:
      // empty -> 1 -> 2, then refused.
      PolicyApprover[] itself = new PolicyApprover[1];
      itself[0] =
          PolicyApprover.create(
              policy ->
                  policy
                      .engine(request -> Verdict.delegate("self"))
                      .delegate("self", request -> itself[0].approve(request))
                      .maxDepth(2));

      assertThat(denialOf(itself[0].approve(asking()))).contains("loop");
    }
  }

  @Nested
  @DisplayName("putting one together")
  class Building {

    @Test
    void an_approver_without_an_engine_is_refused_at_construction() {
      assertThatThrownBy(
              () -> PolicyApprover.create(policy -> policy.delegate("humans", r -> null)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("needs an engine");
    }

    @Test
    @DisplayName("registering a name twice is refused, never silently overwritten")
    void two_approvers_cannot_share_a_name() {
      Approver strict = request -> Awaited.ready(ApprovalResult.denied("no"));
      Approver lenient = request -> Awaited.ready(ApprovalResult.approved());

      assertThatThrownBy(
              () ->
                  PolicyApprover.create(
                      policy ->
                          policy
                              .engine(request -> Verdict.approve())
                              .delegate("review", strict)
                              .delegate("review", lenient)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already registered");
    }

    @Test
    @DisplayName(
        "a max depth below one is refused at construction, since no delegation could ever run")
    void a_max_depth_below_one_is_refused_at_construction() {
      Map<String, Approver> empty = Map.of();

      assertThatThrownBy(() -> new PolicyApprover(request -> Verdict.approve(), empty, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maxDepth must be at least 1");
    }

    @Test
    @DisplayName("the allowlist is a copy, so it cannot be widened after the gate is built")
    void adding_to_the_map_afterwards_reaches_nothing() {
      Map<String, Approver> registry = new java.util.HashMap<>();
      registry.put("humans", r -> Awaited.ready(ApprovalResult.approved()));
      var gate = new PolicyApprover(request -> Verdict.delegate("sneaky"), registry);

      // Whoever holds the map must not be able to widen a gate that was already built.
      registry.put("sneaky", r -> Awaited.ready(ApprovalResult.approved()));

      assertThat(denialOf(gate.approve(asking()))).contains("sneaky", "not registered");
    }
  }
}
