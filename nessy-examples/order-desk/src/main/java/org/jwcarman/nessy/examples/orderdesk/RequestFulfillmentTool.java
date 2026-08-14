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

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Sends an order to the warehouse and parks — the machine half of the turn (spec §2, §5). The park
 * token rides the wire ONLY as the AMQP correlation id, set by the {@code MessagePostProcessor}
 * handed to {@link RabbitTemplate#convertAndSend}; it never appears in the payload (spec §1: the
 * kernel's "the token is the correlation contract" claim made wire-visible). The warehouse's reply
 * listener preserves that correlation id and resumes this same token when the job completes.
 */
public final class RequestFulfillmentTool implements Tool<RequestFulfillmentTool.Input> {

  private final RabbitTemplate rabbit;

  public RequestFulfillmentTool(RabbitTemplate rabbit) {
    this.rabbit = Objects.requireNonNull(rabbit, "rabbit must not be null");
  }

  /** What the model supplies: the order and the items the warehouse must pick and ship. */
  public record Input(String orderId, List<String> items) {}

  /** The wire payload the warehouse consumes — no token inside it (spec §1). */
  record FulfillmentRequest(String orderId, List<String> items) {}

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
