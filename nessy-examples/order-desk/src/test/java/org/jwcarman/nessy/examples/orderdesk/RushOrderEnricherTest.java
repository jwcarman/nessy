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
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationContext;

/** The one enricher the order-desk grant carries (design of record 2026-08-16-authorization §4). */
class RushOrderEnricherTest {

  private final RushOrderEnricher enricher = new RushOrderEnricher();

  private static AuthorizationContext freshContext() {
    ConversationId id = new ConversationId("order-4711");
    ToolCall call =
        new ToolCall("c1", "request_fulfillment", JsonNodeFactory.instance.objectNode());
    return AuthorizationContext.of(id, "order-desk", call, ConversationState.newConversation(id));
  }

  private static RequestFulfillmentTool.FulfillmentEffect effectOf(List<String> items) {
    return new RequestFulfillmentTool.FulfillmentEffect("4711", items, BigDecimal.TEN);
  }

  @Test
  void leaves_a_small_basket_unflagged() {
    AuthorizationContext extended =
        enricher.enrich(freshContext(), effectOf(List.of("lantern", "rope")));

    assertThat(extended.get(RushOrderEnricher.RUSH_ORDER)).isEmpty();
  }

  @Test
  void flags_a_basket_of_three_or_more_items_as_rush() {
    AuthorizationContext extended =
        enricher.enrich(freshContext(), effectOf(List.of("lantern", "rope", "compass")));

    assertThat(extended.get(RushOrderEnricher.RUSH_ORDER)).contains(true);
  }

  @Test
  void carries_its_own_report_display_name() {
    assertThat(enricher.displayName()).contains("rush-order flag");
  }
}
