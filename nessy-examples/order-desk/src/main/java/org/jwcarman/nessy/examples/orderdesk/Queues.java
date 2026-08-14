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

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The three queue names, stated once (spec §4), and the durable {@link Queue} beans that declare
 * them on the broker. All three publish over the default exchange — a queue's own name doubles as
 * its routing key — so no exchange or binding beans are needed.
 */
@Configuration
public class Queues {

  /** Order lifecycle events, published as JSON (spec §2, §3). */
  public static final String ORDERS = "orders";

  /** Fulfillment jobs, correlation id = the park token (spec §2). */
  public static final String FULFILLMENT_REQUESTS = "fulfillment-requests";

  /** The warehouse's progress and completion replies, correlation id preserved (spec §2, §5). */
  public static final String FULFILLMENT_REPLIES = "fulfillment-replies";

  @Bean
  Queue orders() {
    return new Queue(ORDERS);
  }

  @Bean
  Queue fulfillmentRequests() {
    return new Queue(FULFILLMENT_REQUESTS);
  }

  @Bean
  Queue fulfillmentReplies() {
    return new Queue(FULFILLMENT_REPLIES);
  }
}
