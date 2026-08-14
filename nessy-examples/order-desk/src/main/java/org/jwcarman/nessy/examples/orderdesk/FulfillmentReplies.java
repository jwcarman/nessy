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
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.UnknownParkTokenException;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnEvent;
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
 * <p>Both {@link Harness} and {@code Agent<OrderEvent>} are injected, though only {@code Harness}
 * is ever called: injecting the agent is what guarantees the harness has a built loop before {@link
 * Harness#resume} or {@link Harness#progress} runs (the same precedent night-watchman's original
 * Verbs class established before it was retired in that example's own rework) — {@code Harness}
 * alone gives no such guarantee, since a harness can exist with no agent ever built on it.
 *
 * <p>A {@link RuntimeException} escaping {@link #on(FulfillmentReply, String)} is left to
 * propagate: Boot's default AUTO ack nacks and requeues on listener failure, and that at-least-once
 * redelivery IS the design (spec §4, §5) — this class does not catch broadly to protect it.
 */
@Component
public class FulfillmentReplies {

  private static final Logger LOGGER = LoggerFactory.getLogger(FulfillmentReplies.class);

  private final Harness harness;

  public FulfillmentReplies(Harness harness, Agent<OrderEvent> agent) {
    this.harness = Objects.requireNonNull(harness, "harness must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
  }

  /** The warehouse's reply payload: which beat this is, and the narration for it (spec §5). */
  public record FulfillmentReply(String kind, String text) {}

  @RabbitListener(queues = Queues.FULFILLMENT_REPLIES)
  public void on(FulfillmentReply reply, @Header(AmqpHeaders.CORRELATION_ID) String correlationId) {
    ParkToken token = new ParkToken(correlationId);
    switch (reply.kind()) {
      case "progress" -> {
        boolean delivered = harness.progress(token, reply.text());
        if (!delivered) {
          LOGGER.info("stale progress reply for token {}: {}", token.value(), reply.text());
        }
      }
      case "completed" -> resume(token, reply.text());
      default -> LOGGER.warn("unknown reply kind {} for token {}", reply.kind(), token.value());
    }
  }

  private void resume(ParkToken token, String text) {
    try {
      harness.resume(
          token,
          new ToolResolution.Completed(ToolResult.ok(text)),
          turnEvent -> {
            switch (turnEvent) {
              case TurnEvent.ToolCallCompleted(ToolCall call, ToolResult result) ->
                  LOGGER.info(
                      "call {} completed: {}", call.name(), result.isError() ? "error" : "ok");
              default -> {}
            }
          });
    } catch (UnknownParkTokenException e) {
      LOGGER.info("stale completion reply for token {}: {}", token.value(), e.getMessage());
    }
  }
}
