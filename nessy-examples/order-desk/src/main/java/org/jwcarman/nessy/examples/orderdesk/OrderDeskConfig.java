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
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.memory.Memory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The nessy wiring — one bean, the typed agent (spec §5, §9.6: {@code Agent<OrderEvent>}, not
 * {@code Agent<String>} — the first typed-vocabulary agent in the family). {@code Harness} and
 * {@code Memory} arrive from the starter's autoconfiguration over the durable JDBC store; identity
 * is declared here: the standing orders, the one tool (granted {@link UsagePolicy#allow()} — no
 * human in this loop), and a logging listener on tool progress.
 *
 * <p>The {@link JacksonJsonMessageConverter} bean is declared here too: Boot wires any {@link
 * MessageConverter} bean into both the {@code RabbitTemplate} and the listener container factory
 * automatically, so this is the one place the queue's JSON contract for {@link OrderEvent} is
 * stated.
 */
@Configuration
public class OrderDeskConfig {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrderDeskConfig.class);

  private static final String ORDER_DESK_ORDERS =
      "You are an order desk. Each event you receive is one plain-prose line describing something"
          + " that happened to an order; every event you see for a given order belongs to that"
          + " order's own conversation. When an OrderPlaced event arrives, request fulfillment"
          + " with your tool. Answer inquiries using only this order's own history. Be terse.";

  @Bean
  Agent<OrderEvent> agent(Harness harness, Memory memory, RabbitTemplate rabbit) {
    return harness
        .agent(OrderEvent.class)
        .name("order-desk")
        .model("claude-sonnet-4-5")
        .systemPrompt(ORDER_DESK_ORDERS)
        .memory(memory)
        .renderer(ORDER_EVENT_RENDERER)
        .tools(ToolGrant.grant(new RequestFulfillmentTool(rabbit), UsagePolicy.allow()))
        .onToolProgressAsync(progress -> LOGGER.info("tool progress: {}", progress))
        .build();
  }

  // The root README's recommended idiom (spec §5): a sealed switch, one arm per OrderEvent
  // variant, in place of the typed-vocabulary default, InputRenderer.json(mapper). The default
  // would hand the model a tag line plus canonical JSON — "[order_placed]\n{"orderId":"4711",
  // "items":["lantern","rope"]}" — accurate but unread as prose. This renders the same event as
  // one plain sentence the model reads the way a person would: "New order 4711: lantern, rope."
  // Record deconstruction patterns bind each variant's components directly, no accessor calls.
  private static final InputRenderer<OrderEvent> ORDER_EVENT_RENDERER =
      event -> {
        String line =
            switch (event) {
              case OrderEvent.OrderPlaced(String orderId, List<String> items) ->
                  "New order " + orderId + ": " + String.join(", ", items);
              case OrderEvent.PaymentCleared(String orderId) ->
                  "Payment cleared for order " + orderId + ".";
              case OrderEvent.AddressChanged(String orderId, String newAddress) ->
                  "Order " + orderId + "'s shipping address changed to " + newAddress + ".";
              case OrderEvent.CustomerInquiry(String orderId, String question) ->
                  "Order " + orderId + " inquiry: " + question;
            };
        return List.<ContentBlock>of(new TextBlock(line));
      };

  // The __TypeId__ header's class is deserialized by name; Jackson's default mapper refuses
  // any package it hasn't been told to trust (CVE-driven default, spring-amqp's own
  // TRUSTED_PACKAGES starts at just java.util/java.lang), so this module's own package has to
  // be added explicitly or every OrderEvent, FulfillmentRequest, and FulfillmentReply message
  // fails to convert on receipt — a listener-side failure Task 5's smoke test is what first
  // exercised, since publish-only tests never trip the trust check.
  @Bean
  MessageConverter messageConverter() {
    DefaultJacksonJavaTypeMapper typeMapper = new DefaultJacksonJavaTypeMapper();
    typeMapper.addTrustedPackages(getClass().getPackageName());
    JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
    converter.setJavaTypeMapper(typeMapper);
    return converter;
  }
}
