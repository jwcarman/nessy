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
package org.jwcarman.nessy.examples.orderdesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationContext;

/**
 * The order desk's threshold policy (design of record 2026-08-16-authorization §5): allow routine
 * orders straight through, hold anything over the line for a human — a stricter line for a rush
 * order, since {@link RushOrderEnricher} already flagged it as worth a second look.
 */
class OrderApprovalPolicyTest {

  private final OrderApprovalPolicy policy = new OrderApprovalPolicy();

  private static AuthorizationContext freshContext() {
    ConversationId id = new ConversationId("order-4711");
    ToolCall call =
        new ToolCall("c1", "request_fulfillment", JsonNodeFactory.instance.objectNode());
    return AuthorizationContext.of(id, "order-desk", call, ConversationState.newConversation(id));
  }

  private static RequestFulfillmentTool.FulfillmentEffect effectOf(BigDecimal total) {
    return new RequestFulfillmentTool.FulfillmentEffect("4711", List.of("lantern", "rope"), total);
  }

  @Nested
  class A_routine_order {

    @Test
    void is_allowed_at_or_below_the_standard_threshold() {
      PolicyDecision decision =
          policy.evaluate(freshContext(), effectOf(OrderApprovalPolicy.STANDARD_THRESHOLD));

      assertThat(decision).isInstanceOf(PolicyDecision.Allow.class);
    }

    @Test
    void requires_approval_above_the_standard_threshold() {
      BigDecimal over = OrderApprovalPolicy.STANDARD_THRESHOLD.add(BigDecimal.ONE);

      PolicyDecision decision = policy.evaluate(freshContext(), effectOf(over));

      assertThat(decision).isInstanceOf(PolicyDecision.RequireApproval.class);
    }
  }

  @Nested
  class A_rush_order {

    private static AuthorizationContext flaggedContext() {
      return freshContext().with(RushOrderEnricher.RUSH_ORDER, true);
    }

    @Test
    void is_allowed_at_or_below_its_own_stricter_threshold() {
      PolicyDecision decision =
          policy.evaluate(flaggedContext(), effectOf(OrderApprovalPolicy.RUSH_THRESHOLD));

      assertThat(decision).isInstanceOf(PolicyDecision.Allow.class);
    }

    @Test
    void requires_approval_above_its_own_stricter_threshold_even_under_the_standard_one() {
      BigDecimal betweenTheTwoThresholds = OrderApprovalPolicy.RUSH_THRESHOLD.add(BigDecimal.ONE);
      assertThat(betweenTheTwoThresholds).isLessThan(OrderApprovalPolicy.STANDARD_THRESHOLD);

      PolicyDecision decision =
          policy.evaluate(flaggedContext(), effectOf(betweenTheTwoThresholds));

      assertThat(decision).isInstanceOf(PolicyDecision.RequireApproval.class);
    }
  }
}
