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
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The queue as driver (spec §2, §4): a message landing on {@link Queues#ORDERS} is what initiates a
 * turn — no human present, no clock. Each event is {@code tell}-ed to the conversation minted from
 * its own order id, so every event about one order lands in that order's own story (spec §3).
 *
 * <p>Acknowledgement is Boot's default AUTO — a ruling, not an omission (spec §4): the container
 * acks on successful return of {@link #on(OrderEvent)} and requeues on failure or death. There is
 * no ack code anywhere in this class on purpose; at-least-once redelivery plus the fold's own
 * idempotency is the lesson (spec §1).
 */
@Component
public class OrderDesk {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrderDesk.class);

  private final Agent<OrderEvent> agent;

  public OrderDesk(Agent<OrderEvent> agent) {
    this.agent = Objects.requireNonNull(agent, "agent must not be null");
  }

  @RabbitListener(queues = Queues.ORDERS)
  public void on(OrderEvent event) {
    String orderId = event.orderId();
    LOGGER.info("order {} begins: {}", orderId, event);
    agent
        .conversation(new ConversationId("order-" + orderId))
        .tell(event, TurnObserver.logging(LOGGER, "order " + orderId));
  }
}
