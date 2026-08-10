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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.ToolCall;
import org.jwcarman.nessy.api.ToolResult;

class UsagePolicyTest {

  private static final SessionState STATE = SessionState.newSession(new SessionId("s1"));

  private static ToolCall spendCall(int amount) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.set("amount", IntNode.valueOf(amount));
    return new ToolCall("c1", "spend", args);
  }

  @Nested
  class Factories {

    @Test
    void allow_always_allows() {
      UsagePolicy policy = UsagePolicy.allow();

      assertThat(policy.evaluate(spendCall(1), STATE)).isEqualTo(new PolicyDecision.Allow());
    }

    @Test
    void deny_always_denies_with_the_same_reason() {
      UsagePolicy policy = UsagePolicy.deny("no budget");

      assertThat(policy.evaluate(spendCall(1), STATE))
          .isEqualTo(new PolicyDecision.Deny("no budget"));
      assertThat(policy.evaluate(spendCall(999), STATE))
          .isEqualTo(new PolicyDecision.Deny("no budget"));
    }

    @Test
    void require_approval_always_defers() {
      UsagePolicy policy = UsagePolicy.requireApproval();

      assertThat(policy.evaluate(spendCall(1), STATE))
          .isEqualTo(new PolicyDecision.RequireApproval());
    }
  }

  @Nested
  class Contextual_policies {

    /** A policy that behaves like a spend cap: allow under the limit, deny at or over it. */
    private static UsagePolicy approveUnder(int limit) {
      return (call, state) -> {
        int amount = call.arguments().get("amount").asInt();
        return amount < limit
            ? new PolicyDecision.Allow()
            : new PolicyDecision.Deny("amount " + amount + " exceeds limit " + limit);
      };
    }

    @Test
    void a_call_under_the_limit_is_allowed() {
      UsagePolicy policy = approveUnder(100);

      assertThat(policy.evaluate(spendCall(50), STATE)).isEqualTo(new PolicyDecision.Allow());
    }

    @Test
    void a_call_at_or_over_the_limit_is_denied() {
      UsagePolicy policy = approveUnder(100);

      assertThat(policy.evaluate(spendCall(100), STATE))
          .isEqualTo(new PolicyDecision.Deny("amount 100 exceeds limit 100"));
    }
  }

  @Nested
  class Grant_derivation {

    private static final class Recorder implements Tool<Object> {
      private final boolean needsApproval;

      Recorder(boolean needsApproval) {
        this.needsApproval = needsApproval;
      }

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
      public boolean requiresApproval() {
        return needsApproval;
      }

      @Override
      public Awaited<ToolResult> execute(Object input, ToolContext context) {
        return Awaited.ready(ToolResult.ok("recorded"));
      }
    }

    @Test
    void a_tool_that_requires_approval_derives_a_require_approval_grant() {
      ToolGrant grant = ToolGrant.grant(new Recorder(true));

      assertThat(grant.policy().evaluate(spendCall(1), STATE))
          .isEqualTo(new PolicyDecision.RequireApproval());
    }

    @Test
    void a_tool_that_does_not_require_approval_derives_an_allow_grant() {
      ToolGrant grant = ToolGrant.grant(new Recorder(false));

      assertThat(grant.policy().evaluate(spendCall(1), STATE))
          .isEqualTo(new PolicyDecision.Allow());
    }

    @Test
    void with_replaces_only_the_policy() {
      Recorder tool = new Recorder(true);
      ToolGrant derived = ToolGrant.grant(tool);

      ToolGrant tightened = derived.with(UsagePolicy.deny("locked down"));

      assertThat(tightened.tool()).isSameAs(tool);
      assertThat(tightened.policy().evaluate(spendCall(1), STATE))
          .isEqualTo(new PolicyDecision.Deny("locked down"));
    }
  }

  @Nested
  class Validation {

    @Test
    void a_grant_rejects_a_null_tool() {
      assertThatThrownBy(() -> new ToolGrant(null, UsagePolicy.allow()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("tool");
    }

    @Test
    void a_grant_rejects_a_null_policy() {
      assertThatThrownBy(() -> new ToolGrant(new Grant_derivation.Recorder(false), null))
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
