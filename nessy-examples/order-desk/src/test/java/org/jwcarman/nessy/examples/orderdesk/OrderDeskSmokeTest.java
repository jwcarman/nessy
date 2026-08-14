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
import org.jwcarman.nessy.HarnessBuilder;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationSnapshot;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.api.conversation.Usage;
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
 * token, the reply queue narrates and then resumes, and the durable conversation remembers.
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

    publishReply(token, "progress", "picking 2 items for order 4711…");

    await()
        .atMost(ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(PROGRESS)
                    .isNotEmpty()
                    .anyMatch(progress -> progress.message().contains("picking")));

    publishReply(token, "completed", "Order 4711 fulfilled — " + trackingMarker);

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

    String order4711Transcript = transcriptOf("order-4711");
    assertThat(order4711Transcript).contains(trackingMarker);

    // Duplicate-reply idempotency: the fold's replay protection against the wire (spec §7).
    int settledCalls = provider.calls();
    publishReply(token, "completed", "Order 4711 fulfilled — " + trackingMarker);
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
    assertThat(order4711Transcript).doesNotContain("compass");
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
      HarnessBuilder builder =
          Nessy.harness(provider).onToolProgress(PROGRESS::add).onToolFinished(FINISHED::add);
      observations.ifAvailable(builder::observations);
      return builder.build();
    }
  }

  /**
   * Serves calls by index (spec §7): call 1 asks for {@code request_fulfillment} on order 4711;
   * call 2 (after the warehouse's reply resumes the park) answers with the tracking marker; every
   * later call is a short all-quiet answer with no tool use — the isolation lesson only needs order
   * 9000's turn to settle, not to re-drive fulfillment (task-5 brief: "the agent chose not to
   * fulfill" is fine here).
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
                        new ToolCall("c1", "request_fulfillment", fulfillmentArguments())),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));
            case 2 ->
                List.of(
                    new ModelEvent.TextChunk("Order 4711 fulfilled — NESSY-TEST-4711"),
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

    private static JsonNode fulfillmentArguments() {
      ObjectNode arguments = JsonNodeFactory.instance.objectNode();
      arguments.put("orderId", "4711");
      arguments.putArray("items").add("lantern").add("rope");
      return arguments;
    }
  }
}
