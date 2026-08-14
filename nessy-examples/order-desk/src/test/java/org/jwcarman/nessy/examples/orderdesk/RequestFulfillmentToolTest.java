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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * The tool that sends an order to the warehouse and parks — the correlation-contract test (spec
 * §1): the park token rides the wire ONLY as the AMQP correlation id, never in the payload.
 */
class RequestFulfillmentToolTest {

  private static ToolContext context() {
    ToolCall call =
        new ToolCall("call-1", "request_fulfillment", JsonNodeFactory.instance.objectNode());
    return new ToolContext(ConversationId.generate(), call, EventEmitter.noop());
  }

  @Nested
  class Identity {

    @Test
    void names_itself_request_fulfillment_over_its_input_record() {
      RequestFulfillmentTool tool = new RequestFulfillmentTool(new RecordingRabbitTemplate());

      assertThat(tool.name()).isEqualTo("request_fulfillment");
      assertThat(tool.inputType()).isEqualTo(RequestFulfillmentTool.Input.class);
    }
  }

  @Nested
  class Execution {

    @Test
    void parks_and_carries_the_token_only_as_the_correlation_id() {
      RecordingRabbitTemplate rabbit = new RecordingRabbitTemplate();
      RequestFulfillmentTool tool = new RequestFulfillmentTool(rabbit);
      RequestFulfillmentTool.Input input =
          new RequestFulfillmentTool.Input("4711", List.of("lantern", "rope"));

      Awaited<ToolResult> awaited = tool.execute(input, context());

      assertThat(awaited).isInstanceOf(Awaited.Parked.class);
      ParkToken token = ((Awaited.Parked<ToolResult>) awaited).token();

      assertThat(rabbit.capturedRoutingKey).isEqualTo(Queues.FULFILLMENT_REQUESTS);
      assertThat(rabbit.capturedPayload)
          .isEqualTo(
              new RequestFulfillmentTool.FulfillmentRequest("4711", List.of("lantern", "rope")));

      MessageProperties properties = new MessageProperties();
      Message message =
          rabbit.capturedPostProcessor.postProcessMessage(new Message(new byte[0], properties));

      assertThat(message.getMessageProperties().getCorrelationId()).isEqualTo(token.value());
    }
  }

  /**
   * A hand-rolled recording double (house rule: no mocking library) — captures exactly what {@link
   * RequestFulfillmentTool#execute} hands to the wire, and nothing else; every other method is
   * unused by the tool under test and left to {@code RabbitTemplate}'s own implementation.
   */
  private static final class RecordingRabbitTemplate extends RabbitTemplate {

    private String capturedRoutingKey;
    private Object capturedPayload;
    private MessagePostProcessor capturedPostProcessor;

    @Override
    public void convertAndSend(
        String routingKey, Object payload, MessagePostProcessor postProcessor) {
      this.capturedRoutingKey = routingKey;
      this.capturedPayload = payload;
      this.capturedPostProcessor = postProcessor;
    }
  }
}
