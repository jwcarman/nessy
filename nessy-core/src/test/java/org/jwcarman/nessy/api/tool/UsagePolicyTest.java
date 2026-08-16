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
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;

class UsagePolicyTest {

  private static final ConversationId CONVERSATION_ID = new ConversationId("s1");
  private static final ConversationState STATE = ConversationState.newConversation(CONVERSATION_ID);

  private static ToolCall spendCall(int amount) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.set("amount", IntNode.valueOf(amount));
    return new ToolCall("c1", "spend", args);
  }

  private static AuthzContext contextFor(ToolCall call) {
    return AuthzContext.of(CONVERSATION_ID, "test-agent", call, STATE);
  }

  @Nested
  class Factories {

    @Test
    void allow_always_allows() {
      UsagePolicy<Object> policy = UsagePolicy.allow();
      ToolCall call = spendCall(1);

      assertThat(policy.evaluate(contextFor(call), call)).isEqualTo(new PolicyDecision.Allow());
    }

    @Test
    void deny_always_denies_with_the_same_reason() {
      UsagePolicy<Object> policy = UsagePolicy.deny("no budget");
      ToolCall first = spendCall(1);
      ToolCall second = spendCall(999);

      assertThat(policy.evaluate(contextFor(first), first))
          .isEqualTo(new PolicyDecision.Deny("no budget"));
      assertThat(policy.evaluate(contextFor(second), second))
          .isEqualTo(new PolicyDecision.Deny("no budget"));
    }

    @Test
    void require_approval_always_defers() {
      UsagePolicy<Object> policy = UsagePolicy.requireApproval();
      ToolCall call = spendCall(1);

      assertThat(policy.evaluate(contextFor(call), call))
          .isEqualTo(new PolicyDecision.RequireApproval());
    }

    @Test
    void allow_returns_the_same_instance_every_time() {
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
    private static UsagePolicy<Object> approveUnder(int limit) {
      return UsagePolicy.of(
          (context, effect) -> {
            int amount = context.call().arguments().get("amount").asInt();
            return amount < limit
                ? new PolicyDecision.Allow()
                : new PolicyDecision.Deny("amount " + amount + " exceeds limit " + limit);
          });
    }

    @Test
    void a_call_under_the_limit_is_allowed() {
      UsagePolicy<Object> policy = approveUnder(100);
      ToolCall call = spendCall(50);

      assertThat(policy.evaluate(contextFor(call), call)).isEqualTo(new PolicyDecision.Allow());
    }

    @Test
    void a_call_at_or_over_the_limit_is_denied() {
      UsagePolicy<Object> policy = approveUnder(100);
      ToolCall call = spendCall(100);

      assertThat(policy.evaluate(contextFor(call), call))
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
    void grant_rejects_a_null_tool() {
      UsagePolicy<Object> policy = UsagePolicy.allow();

      assertThatThrownBy(() -> ToolGrant.grant(null, policy))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("tool");
    }

    @Test
    void grant_rejects_a_null_policy() {
      Recorder tool = new Recorder();

      assertThatThrownBy(() -> ToolGrant.grant(tool, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("policy");
    }

    @Test
    void grant_states_the_tool_and_policy_it_was_given() {
      Recorder tool = new Recorder();
      UsagePolicy<Object> policy = UsagePolicy.requireApproval();

      ToolGrant grant = ToolGrant.grant(tool, policy);

      assertThat(grant.tool()).isSameAs(tool);
      assertThat(grant.policy()).isSameAs(policy);
    }
  }

  @Nested
  class Validation {

    @Test
    void a_grant_rejects_a_null_tool() {
      UsagePolicy<Object> policy = UsagePolicy.allow();

      assertThatThrownBy(() -> new ToolGrant(null, policy, List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("tool");
    }

    @Test
    void a_grant_rejects_a_null_policy() {
      Grant_construction.Recorder tool = new Grant_construction.Recorder();

      assertThatThrownBy(() -> new ToolGrant(tool, null, List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("policy");
    }

    @Test
    void a_deny_decision_rejects_a_blank_reason() {
      assertThatThrownBy(() -> new PolicyDecision.Deny(" "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("reason");
    }

    @Test
    void a_deny_decision_rejects_a_null_reason() {
      assertThatThrownBy(() -> new PolicyDecision.Deny(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("reason");
    }
  }
}
