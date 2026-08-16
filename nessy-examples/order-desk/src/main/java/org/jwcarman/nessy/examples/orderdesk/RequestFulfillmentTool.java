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

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.tool.EffectfulTool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Sends an order to the warehouse and parks — the machine half of the turn (spec §2, §5). The park
 * token rides the wire ONLY as the AMQP correlation id, set by the {@code MessagePostProcessor}
 * handed to {@link RabbitTemplate#convertAndSend}; it never appears in the payload (spec §1: the
 * kernel's "the token is the correlation contract" claim made wire-visible). The warehouse's reply
 * listener preserves that correlation id and resumes this same token when the job completes.
 *
 * <p>The typed tier (design of record 2026-08-16-authorization §2): {@link #effect(Input)} renders
 * a {@link FulfillmentEffect} priced by {@link OrderPricing} — looked up here, from the tool's own
 * trusted collaborator, never taken from the model's own arguments — so {@code OrderDeskConfig}'s
 * grant can weld a threshold policy to it at compile time.
 */
public final class RequestFulfillmentTool
    implements EffectfulTool<
        RequestFulfillmentTool.Input, RequestFulfillmentTool.FulfillmentEffect> {

  private final RabbitTemplate rabbit;
  private final OrderPricing pricing;

  public RequestFulfillmentTool(RabbitTemplate rabbit, OrderPricing pricing) {
    this.rabbit = Objects.requireNonNull(rabbit, "rabbit must not be null");
    this.pricing = Objects.requireNonNull(pricing, "pricing must not be null");
  }

  /** What the model supplies: the order and the items the warehouse must pick and ship. */
  public record Input(String orderId, List<String> items) {

    public Input {
      if (orderId == null || orderId.isBlank()) {
        throw new IllegalArgumentException("orderId must not be blank");
      }
      Objects.requireNonNull(items, "items must not be null");
      items = List.copyOf(items);
    }
  }

  /** The wire payload the warehouse consumes — no token inside it (spec §1). */
  record FulfillmentRequest(String orderId, List<String> items) {

    FulfillmentRequest {
      if (orderId == null || orderId.isBlank()) {
        throw new IllegalArgumentException("orderId must not be blank");
      }
      Objects.requireNonNull(items, "items must not be null");
      items = List.copyOf(items);
    }
  }

  /**
   * The order desk's own effect statement (design of record 2026-08-16-authorization §2, §8):
   * priced by {@link OrderPricing}, never by the model. Its {@link #toString()} is what an approver
   * or an audit record reads — a skimmable sentence, not a record dump.
   */
  public record FulfillmentEffect(String orderId, List<String> items, BigDecimal orderTotal) {

    public FulfillmentEffect {
      if (orderId == null || orderId.isBlank()) {
        throw new IllegalArgumentException("orderId must not be blank");
      }
      Objects.requireNonNull(items, "items must not be null");
      items = List.copyOf(items);
      Objects.requireNonNull(orderTotal, "orderTotal must not be null");
    }

    @Override
    public String toString() {
      return "Fulfill order " + orderId + " (" + String.join(", ", items) + ") — $" + orderTotal;
    }
  }

  @Override
  public String name() {
    return "request_fulfillment";
  }

  @Override
  public String description() {
    return "Sends the order to the warehouse. Slow: confirmation arrives later.";
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public FulfillmentEffect effect(Input input) {
    return new FulfillmentEffect(input.orderId(), input.items(), pricing.totalFor(input.items()));
  }

  @Override
  public Awaited<ToolResult> execute(Input input, ToolContext context) {
    ParkToken token = ParkToken.generate();
    rabbit.convertAndSend(
        Queues.FULFILLMENT_REQUESTS,
        new FulfillmentRequest(input.orderId(), input.items()),
        message -> {
          message.getMessageProperties().setCorrelationId(token.value());
          return message;
        });
    context.progress("fulfillment requested; awaiting the warehouse");
    return Awaited.parked(token);
  }
}
