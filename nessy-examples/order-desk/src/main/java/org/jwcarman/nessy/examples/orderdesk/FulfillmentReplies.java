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

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.function.Supplier;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.UnknownParkTokenException;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * The machine half of the turn, arriving over AMQP (spec §2, §5): the reply listener routes by
 * {@link FulfillmentReply#kind()} onto the two callback verbs the kernel already defines — {@code
 * progress} narrates, {@code completed} resumes the parked call with {@link
 * ToolResolution.Completed} — and the turn finishes in whatever process the reply reached, on this
 * listener's own thread; there is no detached-turn machinery here on purpose (spec §4).
 *
 * <p>Callbacks go through {@code Agent<OrderEvent>} directly now (design of record amendment,
 * 2026-08-14) — there is no harness-level {@code resume}/{@code progress} to route through any
 * more; the agent that parked a call is the only object with the loop and registry a callback
 * needs.
 *
 * <p>A {@link RuntimeException} escaping {@link #on(FulfillmentReply, String)} is left to
 * propagate: Boot's default AUTO ack nacks and requeues on listener failure, and that at-least-once
 * redelivery IS the design (spec §4, §5) — this class does not catch broadly to protect it.
 */
@Component
public class FulfillmentReplies {

  private static final Logger LOGGER = LoggerFactory.getLogger(FulfillmentReplies.class);

  private final Agent<OrderEvent> agent;

  public FulfillmentReplies(Agent<OrderEvent> agent) {
    this.agent = Objects.requireNonNull(agent, "agent must not be null");
  }

  /** The warehouse's reply payload: which beat this is, and the narration for it (spec §5). */
  public record FulfillmentReply(String kind, String text) {

    /** The progress beat: narration only, drop-legal (spec §5). */
    public static final String PROGRESS = "progress";

    /** The terminal beat: resumes the parked call (spec §5). */
    public static final String COMPLETED = "completed";

    public FulfillmentReply {
      if (kind == null || kind.isBlank()) {
        throw new IllegalArgumentException("kind must not be blank");
      }
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  @RabbitListener(queues = Queues.FULFILLMENT_REPLIES)
  public void on(FulfillmentReply reply, @Header(AmqpHeaders.CORRELATION_ID) String correlationId) {
    ParkToken token = new ParkToken(correlationId);
    switch (reply.kind()) {
      case FulfillmentReply.PROGRESS -> {
        boolean delivered = agent.progress(token, reply.text());
        if (!delivered) {
          LOGGER.info("stale progress reply for token {}: {}", token.value(), reply.text());
        }
      }
      case FulfillmentReply.COMPLETED -> resume(token, reply.text());
      default -> LOGGER.warn("unknown reply kind {} for token {}", reply.kind(), token.value());
    }
  }

  /**
   * Resumes the parked call and narrates the resumed segment through {@link
   * TurnObserver#logging(Logger, Supplier)} — the same says/tool/ends/failed shape {@link
   * OrderDesk#on(OrderEvent)}'s call already uses, now shared instead of hand-rolled.
   *
   * <p>The prefix supplier is what makes this work: {@link Agent#resume} narrates every event on
   * this same thread before it returns, so a prefix that only became known from its own return
   * value would be too late for every line the drive emits — that value doesn't exist yet while
   * those lines are being written. The order id is resolved BEFORE the call, instead, via {@link
   * Agent#peek}: a non-consuming read of the still-parked call's {@code request_fulfillment}
   * arguments (the same {@code orderId} the model was handed when it made the call {@link
   * OrderDesk#on(OrderEvent)} started this conversation with), so the prefix is settled before the
   * drive ever narrates a line. A peek that finds nothing means the same early-reply race the
   * {@code catch} below handles — {@link Agent#resume} is about to throw for the identical reason,
   * before any line narrates, so the token's own value stands in as a prefix that is built but
   * never used.
   *
   * <p>{@link UnknownParkTokenException} is logged and RETHROWN, not swallowed: the registry
   * survives resolution (a resolved park drains only when {@link Agent#resume} actually drives it,
   * inside this call), so the only way this exception reaches here is a reply that arrived before
   * the loop had registered the park at all — the early-reply race, not a stale or duplicate one.
   * Rethrowing lets Boot's default AUTO ack nack and requeue the message, so redelivery finds the
   * park registered and resumes it; swallowing here would ack the message away and leave the order
   * parked forever.
   */
  private void resume(ParkToken token, String text) {
    String orderId = orderIdOf(token);
    try {
      agent.resume(
          token,
          new ToolResolution.Completed(ToolResult.ok(text)),
          TurnObserver.logging(LOGGER, () -> "order " + orderId));
    } catch (UnknownParkTokenException e) {
      LOGGER.info("early completion reply for token {}: {}", token.value(), e.getMessage());
      throw e;
    }
  }

  /**
   * The order id off the still-parked {@code request_fulfillment} call's own arguments, read
   * without consuming the park; falls back to the token's value on a peek miss, which only happens
   * in the early-reply race {@link #resume(ParkToken, String)}'s {@code catch} already handles.
   */
  private String orderIdOf(ParkToken token) {
    return agent.peek(token).map(FulfillmentReplies::orderIdArgument).orElseGet(token::value);
  }

  private static String orderIdArgument(ParkedCall parked) {
    JsonNode orderId = parked.call().arguments().get("orderId");
    return orderId == null ? parked.token().value() : orderId.asText();
  }
}
