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

import java.util.Objects;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * The demo's fake world (spec §5, the coupon-tool ethos: obviously fake, structurally honest). One
 * listener on {@link Queues#FULFILLMENT_REQUESTS} plays every warehouse in existence: it always has
 * the items, it always ships, and it always narrates the same two beats — picking, then shipped —
 * with a tracking number derived deterministically from the order id rather than anything a real
 * carrier would issue. Nothing here is clever; it exists so the demo needs no second process to be
 * honest about cross-process delivery (spec §5).
 *
 * <p>{@code @ConditionalOnProperty(name = "order-desk.warehouse.enabled", havingValue = "true",
 * matchIfMissing = true)}: on by default so the demo needs nothing extra, but Task 5's smoke test
 * disables it so the test itself can play warehouse deterministically instead of racing this one.
 */
@Component
@ConditionalOnProperty(
    name = "order-desk.warehouse.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class Warehouse {

  private final RabbitTemplate rabbit;

  public Warehouse(RabbitTemplate rabbit) {
    this.rabbit = Objects.requireNonNull(rabbit, "rabbit must not be null");
  }

  @RabbitListener(queues = Queues.FULFILLMENT_REQUESTS)
  public void on(
      RequestFulfillmentTool.FulfillmentRequest request,
      @Header(AmqpHeaders.CORRELATION_ID) String correlationId) {
    reply(
        correlationId,
        new FulfillmentReplies.FulfillmentReply(
            FulfillmentReplies.FulfillmentReply.PROGRESS,
            "picking " + request.items().size() + " items for order " + request.orderId() + "…"));
    reply(
        correlationId,
        new FulfillmentReplies.FulfillmentReply(
            FulfillmentReplies.FulfillmentReply.COMPLETED,
            "shipped: tracking NESSY-" + trackingSuffix(request.orderId())));
  }

  private void reply(String correlationId, FulfillmentReplies.FulfillmentReply payload) {
    rabbit.convertAndSend(
        Queues.FULFILLMENT_REPLIES,
        payload,
        message -> {
          message.getMessageProperties().setCorrelationId(correlationId);
          return message;
        });
  }

  /**
   * A deterministic, obviously-fake tracking suffix — the same order id always yields the same
   * suffix, with no claim to look like a real carrier's scheme.
   */
  private static String trackingSuffix(String orderId) {
    return Integer.toHexString(orderId.hashCode());
  }
}
