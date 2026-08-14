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
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.ToolProgress;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
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
 * The literal warehouse round trip (spec §7): with the real {@link Warehouse} listener on (the
 * property's own default — {@code order-desk.warehouse.enabled} is stated explicitly here anyway,
 * for a reader who only opens this file), a published {@code OrderPlaced} runs the whole loop end
 * to end with nothing scripted on the AMQP side — the fake warehouse plays itself, replies for
 * real, and the turn completes.
 *
 * <p>A sibling class rather than a {@code @Nested} test on {@link OrderDeskSmokeTest}: the two
 * suites need different {@code order-desk.warehouse.enabled} values, which means different Spring
 * contexts either way, and a separate top-level class sidesteps that other suite's static {@code
 * PROGRESS}/{@code FINISHED} lists entirely instead of sharing and resetting them.
 */
@SpringBootTest(
    properties = {"order-desk.warehouse.enabled=true", "spring.docker.compose.enabled=false"})
@Tag("container")
@Testcontainers
class WarehouseRoundTripSmokeTest {

  private static final List<ToolProgress> PROGRESS = new CopyOnWriteArrayList<>();

  private static final List<ConversationEvent.ToolFinished> FINISHED = new CopyOnWriteArrayList<>();

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired private Agent<OrderEvent> agent;
  @Autowired private RabbitTemplate rabbitTemplate;

  @Test
  void the_real_warehouse_answers_its_own_fulfillment_request() {
    String orderId = "7777";
    String trackingMarker = "NESSY-" + Integer.toHexString(orderId.hashCode());

    rabbitTemplate.convertAndSend(
        Queues.ORDERS, new OrderEvent.OrderPlaced(orderId, List.of("map")));

    await()
        .atMost(ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(agent.snapshot(new ConversationId("order-" + orderId)).status())
                    .isEqualTo(ConversationStatus.COMPLETE));

    assertThat(PROGRESS).isNotEmpty().anyMatch(progress -> progress.message().contains("picking"));
    assertThat(FINISHED)
        .isNotEmpty()
        .anyMatch(
            finished ->
                "request_fulfillment".equals(finished.call().name())
                    && finished.result().content().contains(trackingMarker));
  }

  /**
   * A harness over the scripted provider, in-memory only for the model call — the store and memory
   * beans still come from the starter's real JDBC persistence autoconfiguration over the
   * Testcontainers datasource, exactly {@link OrderDeskSmokeTest}'s own arrangement.
   */
  @TestConfiguration
  static class WarehouseRoundTripTestConfig {

    @Bean
    ScriptedProvider scriptedProvider() {
      return new ScriptedProvider();
    }

    @Bean
    Harness harness(ScriptedProvider provider, ObjectProvider<ObservationRegistry> observations) {
      HarnessBuilder builder =
          Nessy.harness(provider).onToolProgress(PROGRESS::add).onToolFinished(FINISHED::add);
      observations.ifAvailable(builder::observations);
      return builder.build();
    }
  }

  /**
   * Serves calls by index: call 1 asks for {@code request_fulfillment} on order 7777; every call
   * after that — the real {@link Warehouse}'s completion resuming the park, and anything beyond —
   * answers with a short all-quiet text and no tool use, tolerating however many extra calls the
   * real warehouse's two-beat reply (progress, then completed) drives.
   */
  static final class ScriptedProvider implements ModelProvider {

    private final AtomicInteger calls = new AtomicInteger();

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
      arguments.put("orderId", "7777");
      arguments.putArray("items").add("map");
      return arguments;
    }
  }
}
