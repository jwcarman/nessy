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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The vocabulary's offline contract (spec §3, §7): every variant tags itself with its simple name
 * under {@code "type"} and round-trips intact through both jurisdictions the demo actually
 * exercises — the classic {@code com.fasterxml} engine the renderer and starter use today, and the
 * {@code tools.jackson} engine Spring AMQP 4's {@code JacksonJsonMessageConverter} runs on (Task
 * 3+). No broker, no database.
 */
class OrderEventTest {

  private static final ObjectMapper CLASSIC_MAPPER = new ObjectMapper();
  private static final JsonMapper TOOLS_JACKSON_MAPPER = JsonMapper.builder().build();

  static Stream<OrderEvent> variants() {
    return Stream.of(
        new OrderEvent.OrderPlaced("4711", List.of("lantern", "rope")),
        new OrderEvent.PaymentCleared("4711"),
        new OrderEvent.AddressChanged("4711", "42 Wallaby Way"),
        new OrderEvent.CustomerInquiry("4711", "where is my order?"));
  }

  @ParameterizedTest
  @MethodSource("variants")
  void round_trips_through_the_classic_jackson_engine_with_its_type_tag(OrderEvent event)
      throws Exception {
    String json = CLASSIC_MAPPER.writeValueAsString(event);

    OrderEvent restored = CLASSIC_MAPPER.readValue(json, OrderEvent.class);

    assertThat(json).contains("\"type\":\"" + event.getClass().getSimpleName() + "\"");
    assertThat(restored).isEqualTo(event);
    assertThat(restored.orderId()).isEqualTo(event.orderId());
  }

  @ParameterizedTest
  @MethodSource("variants")
  void round_trips_through_the_tools_jackson_engine_the_amqp_converter_uses(OrderEvent event) {
    String json = TOOLS_JACKSON_MAPPER.writeValueAsString(event);

    OrderEvent restored = TOOLS_JACKSON_MAPPER.readValue(json, OrderEvent.class);

    assertThat(json).contains("\"type\":\"" + event.getClass().getSimpleName() + "\"");
    assertThat(restored).isEqualTo(event);
    assertThat(restored.orderId()).isEqualTo(event.orderId());
  }
}
