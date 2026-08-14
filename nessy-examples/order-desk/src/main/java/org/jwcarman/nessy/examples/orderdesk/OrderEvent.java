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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Objects;

/**
 * An order lifecycle event, published as JSON to the {@code orders} queue (spec §2, §3). The
 * Jackson tagging now serves only the AMQP wire — the message converter deserializes it — while
 * {@code OrderDeskConfig}'s renderer turns each event into one plain-prose line for the model
 * instead.
 *
 * <p>The {@code @JsonSubTypes} names are stated explicitly rather than left to default to the
 * simple class name, because the demo publishes these by hand from RabbitMQ's management console
 * (spec §6) — the wire contract is a promise made to a human, not an implementation detail.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = OrderEvent.OrderPlaced.class, name = "OrderPlaced"),
  @JsonSubTypes.Type(value = OrderEvent.PaymentCleared.class, name = "PaymentCleared"),
  @JsonSubTypes.Type(value = OrderEvent.AddressChanged.class, name = "AddressChanged"),
  @JsonSubTypes.Type(value = OrderEvent.CustomerInquiry.class, name = "CustomerInquiry")
})
public sealed interface OrderEvent {

  String orderId();

  /** A new order, with the items it contains. Triggers the one tool call (spec §2). */
  record OrderPlaced(String orderId, List<String> items) implements OrderEvent {

    public OrderPlaced {
      if (orderId == null || orderId.isBlank()) {
        throw new IllegalArgumentException("orderId must not be blank");
      }
      Objects.requireNonNull(items, "items must not be null");
      items = List.copyOf(items);
    }
  }

  /** Payment for the order has settled. */
  record PaymentCleared(String orderId) implements OrderEvent {

    public PaymentCleared {
      if (orderId == null || orderId.isBlank()) {
        throw new IllegalArgumentException("orderId must not be blank");
      }
    }
  }

  /** The order's shipping address changed after it was placed. */
  record AddressChanged(String orderId, String newAddress) implements OrderEvent {

    public AddressChanged {
      if (orderId == null || orderId.isBlank()) {
        throw new IllegalArgumentException("orderId must not be blank");
      }
      Objects.requireNonNull(newAddress, "newAddress must not be null");
    }
  }

  /** A question about the order, answered from the order's own conversation history. */
  record CustomerInquiry(String orderId, String question) implements OrderEvent {

    public CustomerInquiry {
      if (orderId == null || orderId.isBlank()) {
        throw new IllegalArgumentException("orderId must not be blank");
      }
      Objects.requireNonNull(question, "question must not be null");
    }
  }
}
