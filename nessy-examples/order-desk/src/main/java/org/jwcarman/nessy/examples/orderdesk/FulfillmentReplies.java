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
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.UnknownParkTokenException;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
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
   * Resumes the parked call and narrates the resumed segment: a {@link TurnObserver#builder()}
   * composition — settled assistant messages joined into one "says" line the way {@link
   * TurnObserver#logging}'s own says-line does (text blocks joined in order, a blank message
   * contributing nothing), tool completions logged as they land — standing in for {@link
   * TurnObserver#logging} because the order id isn't known until the drive returns, and so can't
   * seed a per-call prefix the way {@link OrderDesk#on(OrderEvent)}'s does. The order id is read
   * back off the returned {@link RunOutcome}'s conversation id rather than threaded in separately,
   * stripping the {@code "order-"} prefix {@link OrderDesk} mints it with, so a resumed segment
   * identifies itself by the same order number the original {@code order N begins}/{@code ends}
   * lines used, matching {@link TurnObserver#logging}'s says/ends/failed shape exactly — unlike the
   * delta-accumulation this replaced, which never produced a says-line at all against a
   * non-streaming provider (deltas are a streaming-provider artifact; {@link
   * org.jwcarman.nessy.api.turn.TurnEvent.AssistantSaid} is emitted regardless).
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
    StringBuilder said = new StringBuilder();
    RunOutcome outcome;
    try {
      outcome =
          agent.resume(
              token,
              new ToolResolution.Completed(ToolResult.ok(text)),
              TurnObserver.builder()
                  .onAssistantSaid(saidEvent -> append(said, joinedText(saidEvent.message())))
                  .onToolCallCompleted(
                      completed ->
                          LOGGER.info(
                              "call {} completed: {}",
                              completed.call().name(),
                              completed.result().isError() ? "error" : "ok"))
                  .build());
    } catch (UnknownParkTokenException e) {
      LOGGER.info("early completion reply for token {}: {}", token.value(), e.getMessage());
      throw e;
    }
    String orderId = orderIdOf(outcome);
    if (!said.isEmpty()) {
      LOGGER.info("order {} says: {}", orderId, said);
    }
    LOGGER.info("order {} ends: {}", orderId, outcome.state().status());
    if (outcome.state().status() == ConversationStatus.FAILED) {
      LOGGER.warn(
          "order {} failed: {}",
          orderId,
          Objects.requireNonNullElse(outcome.state().failureReason(), "unknown failure"));
    }
  }

  /** Skips a blank {@code text} the way {@link TurnObserver#logging} skips a blank message. */
  private static void append(StringBuilder said, String text) {
    if (!text.isBlank()) {
      said.append(text);
    }
  }

  /** The message's {@link TextBlock} content, concatenated in order — mirrors {@code logging}. */
  private static String joinedText(Message message) {
    StringBuilder joined = new StringBuilder();
    for (ContentBlock block : message.content()) {
      if (block instanceof TextBlock(String text)) {
        joined.append(text);
      }
    }
    return joined.toString();
  }

  private static String orderIdOf(RunOutcome outcome) {
    String conversationId = outcome.state().id().value();
    return conversationId.startsWith("order-")
        ? conversationId.substring("order-".length())
        : conversationId;
  }
}
