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

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.ObservationRegistry;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationSnapshot;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.ApprovalRequested;
import org.jwcarman.nessy.api.event.ToolProgress;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole order-desk story against a real broker and a real database (spec §7): a message on
 * {@code orders} drives a turn, the tool parks with the AMQP correlation id carrying the park
 * token, the reply queue narrates and then resumes, and the durable conversation remembers. Also
 * both sides of the authorization threshold (design of record 2026-08-16-authorization §5): a
 * routine order clears straight through with no approval request at all, while a big-basket order
 * crosses {@link OrderApprovalPolicy}'s own line and is handed to the approver before the tool ever
 * runs — auto-approved here, since this module wires no {@code .approver(...)} of its own, but the
 * {@link ApprovalRequested} event proves the gate was actually asked.
 *
 * <p>The warehouse is disabled ({@code order-desk.warehouse.enabled=false}) so THIS test plays
 * warehouse by hand — the deterministic design the brief prefers over racing the real {@link
 * Warehouse} listener for the park token. Docker is required; {@code @Tag("container")} keeps this
 * out of the offline default build (root {@code pom.xml}'s {@code nessy.excludedGroups}).
 */
@SpringBootTest(
    properties = {"order-desk.warehouse.enabled=false", "spring.docker.compose.enabled=false"})
@Tag("container")
@Testcontainers
class OrderDeskSmokeTest {

  private static final long RECEIVE_TIMEOUT_MS = 5_000L;

  /** Heard by the sync listeners the test harness declares. */
  private static final List<ToolProgress> PROGRESS = new CopyOnWriteArrayList<>();

  private static final List<ConversationEvent.ToolFinished> FINISHED = new CopyOnWriteArrayList<>();

  /** Heard whenever a call's policy defers to the approver (design of record 2026-08-16 §5). */
  private static final List<ApprovalRequested> APPROVALS = new CopyOnWriteArrayList<>();

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired private Agent<OrderEvent> agent;
  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private ScriptedOrderDeskProvider provider;

  @Test
  void the_broker_drives_and_the_order_remembers() {
    String trackingMarker = "NESSY-TEST-4711";

    rabbitTemplate.convertAndSend(
        Queues.ORDERS, new OrderEvent.OrderPlaced("4711", List.of("lantern", "rope")));

    await()
        .atMost(ofSeconds(10))
        .untilAsserted(
            () -> {
              ConversationSnapshot snapshot = agent.snapshot(new ConversationId("order-4711"));
              assertThat(snapshot.status()).isEqualTo(ConversationStatus.PARKED);
              assertThat(snapshot.parkedCalls()).hasSize(1);
            });
    ParkedCall parked = agent.snapshot(new ConversationId("order-4711")).parkedCalls().getFirst();
    String token = parked.token().value();

    var requestMessage = rabbitTemplate.receive(Queues.FULFILLMENT_REQUESTS, RECEIVE_TIMEOUT_MS);
    assertThat(requestMessage).isNotNull();
    assertThat(requestMessage.getMessageProperties().getCorrelationId()).isEqualTo(token);

    publishReply(
        token, FulfillmentReplies.FulfillmentReply.PROGRESS, "picking 2 items for order 4711…");

    await()
        .atMost(ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(PROGRESS)
                    .isNotEmpty()
                    .anyMatch(progress -> progress.message().contains("picking")));

    publishReply(
        token,
        FulfillmentReplies.FulfillmentReply.COMPLETED,
        "Order 4711 fulfilled — " + trackingMarker);

    await()
        .atMost(ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(agent.snapshot(new ConversationId("order-4711")).status())
                    .isEqualTo(ConversationStatus.COMPLETE));

    assertThat(FINISHED)
        .isNotEmpty()
        .anyMatch(
            finished ->
                "request_fulfillment".equals(finished.call().name())
                    && finished.result().content().contains(trackingMarker));

    assertThat(transcriptOf("order-4711")).contains(trackingMarker);

    // Duplicate-reply idempotency: the fold's replay protection against the wire (spec §7).
    int settledCalls = provider.calls();
    publishReply(
        token,
        FulfillmentReplies.FulfillmentReply.COMPLETED,
        "Order 4711 fulfilled — " + trackingMarker);
    await().during(ofSeconds(2)).atMost(ofSeconds(6)).until(() -> provider.calls() == settledCalls);

    // Second order isolation: a different conversation, ignorant of 4711 (spec §7).
    rabbitTemplate.convertAndSend(
        Queues.ORDERS, new OrderEvent.OrderPlaced("9000", List.of("compass")));

    await()
        .atMost(ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(agent.snapshot(new ConversationId("order-9000")).status())
                    .isEqualTo(ConversationStatus.COMPLETE));

    String order9000Transcript = transcriptOf("order-9000");
    assertThat(order9000Transcript).doesNotContain("lantern").doesNotContain("rope");
    // Re-read AFTER order 9000 completes: a transcript captured before 9000 existed could never
    // have contained "compass" regardless of isolation, which would make this assertion vacuous.
    assertThat(transcriptOf("order-4711")).doesNotContain("compass");

    // Both sides of the authorization threshold (design of record 2026-08-16-authorization §5):
    // order 4711's own basket (2 items, $300) cleared OrderApprovalPolicy's standard $500 line with
    // no approval request at all — asserted first, before this order's own request could exist, so
    // the later non-vacuous check has something to contrast against.
    assertThat(APPROVALS).isEmpty();

    rabbitTemplate.convertAndSend(
        Queues.ORDERS,
        new OrderEvent.OrderPlaced("9500", List.of("helmet", "boots", "tent", "stove")));

    await()
        .atMost(ofSeconds(10))
        .untilAsserted(
            () -> {
              ConversationSnapshot snapshot = agent.snapshot(new ConversationId("order-9500"));
              assertThat(snapshot.status()).isEqualTo(ConversationStatus.PARKED);
              assertThat(snapshot.parkedCalls()).hasSize(1);
            });

    // The four-item basket ($600) crossed the threshold and was handed to the approver — auto
    // approved (no .approver(...) is wired here), but the request itself proves the gate ran.
    assertThat(APPROVALS)
        .isNotEmpty()
        .anyMatch(requested -> requested.conversationId().equals(new ConversationId("order-9500")));

    ParkedCall bigBasketParked =
        agent.snapshot(new ConversationId("order-9500")).parkedCalls().getFirst();
    publishReply(
        bigBasketParked.token().value(),
        FulfillmentReplies.FulfillmentReply.COMPLETED,
        "Order 9500 fulfilled — NESSY-TEST-9500");

    await()
        .atMost(ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(agent.snapshot(new ConversationId("order-9500")).status())
                    .isEqualTo(ConversationStatus.COMPLETE));
  }

  private void publishReply(String token, String kind, String text) {
    MessagePostProcessor stampCorrelationId =
        message -> {
          message.getMessageProperties().setCorrelationId(token);
          return message;
        };
    rabbitTemplate.convertAndSend(
        Queues.FULFILLMENT_REPLIES,
        new FulfillmentReplies.FulfillmentReply(kind, text),
        stampCorrelationId);
  }

  private String transcriptOf(String conversationId) {
    StringBuilder text = new StringBuilder();
    for (Message message :
        agent.snapshot(new ConversationId(conversationId)).context().messages()) {
      message.content().stream()
          .filter(TextBlock.class::isInstance)
          .map(TextBlock.class::cast)
          .forEach(block -> text.append(block.text()));
    }
    return text.toString();
  }

  /**
   * A harness over the scripted provider, in-memory only for the model call — the store and memory
   * beans still come from the starter's real JDBC persistence autoconfiguration over the
   * Testcontainers datasource. Wins over the starter's own {@code Harness} bean by
   * {@code @ConditionalOnMissingBean(Harness.class)}, which also keeps the real Anthropic provider
   * from ever being constructed — no key, no network.
   */
  @TestConfiguration
  static class OrderDeskTestConfig {

    @Bean
    ScriptedOrderDeskProvider scriptedOrderDeskProvider() {
      return new ScriptedOrderDeskProvider();
    }

    @Bean
    Harness harness(
        ScriptedOrderDeskProvider provider, ObjectProvider<ObservationRegistry> observations) {
      return Nessy.harness(
          h -> {
            h.provider(provider)
                .onToolProgress(PROGRESS::add)
                .onToolFinished(FINISHED::add)
                .onApprovalRequested(APPROVALS::add);
            observations.ifAvailable(h::observations);
          });
    }
  }

  /**
   * Serves calls by index (spec §7): call 1 asks for {@code request_fulfillment} on order 4711;
   * call 2 (after the warehouse's reply resumes the park) answers with the tracking marker; call 3
   * (order 9000) is a short all-quiet answer with no tool use — the isolation lesson only needs
   * that turn to settle, not to re-drive fulfillment (task-5 brief: "the agent chose not to
   * fulfill" is fine here); call 4 asks for {@code request_fulfillment} on order 9500's four-item,
   * over-threshold basket (design of record 2026-08-16-authorization §5); call 5 (after that
   * resume) answers with its own tracking marker; every later call is the same short all-quiet
   * answer as call 3.
   */
  static final class ScriptedOrderDeskProvider implements ModelProvider {

    private final AtomicInteger calls = new AtomicInteger();

    int calls() {
      return calls.get();
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      List<ModelEvent> turn =
          switch (calls.incrementAndGet()) {
            case 1 ->
                List.of(
                    new ModelEvent.ToolUseEmitted(
                        new ToolCall(
                            "c1",
                            "request_fulfillment",
                            fulfillmentArguments("4711", List.of("lantern", "rope")))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));
            case 2 ->
                List.of(
                    new ModelEvent.TextChunk("Order 4711 fulfilled — NESSY-TEST-4711"),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
            case 4 ->
                List.of(
                    new ModelEvent.ToolUseEmitted(
                        new ToolCall(
                            "c2",
                            "request_fulfillment",
                            fulfillmentArguments(
                                "9500", List.of("helmet", "boots", "tent", "stove")))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));
            case 5 ->
                List.of(
                    new ModelEvent.TextChunk("Order 9500 fulfilled — NESSY-TEST-9500"),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
            default ->
                List.of(
                    new ModelEvent.TextChunk("Noted."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
          };
      Iterator<ModelEvent> events = turn.iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          // scripted stream holds no resources to release
        }
      };
    }

    private static JsonNode fulfillmentArguments(String orderId, List<String> items) {
      ObjectNode arguments = JsonNodeFactory.instance.objectNode();
      arguments.put("orderId", orderId);
      var itemsNode = arguments.putArray("items");
      items.forEach(itemsNode::add);
      return arguments;
    }
  }
}
