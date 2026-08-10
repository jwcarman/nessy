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
package org.jwcarman.nessy.api.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the two factory renderers' wire shape: {@code text()}'s pass-through, {@code json()}'s
 * tag+body.
 */
class InputRendererTest {

  record OrderEscalation(String orderId, String reason) {}

  record CustomerSupportTicket(String subject) {}

  @Nested
  class The_text_renderer {

    @Test
    void produces_the_identical_block_message_user_already_produces() {
      InputRenderer<String> renderer = InputRenderer.text();

      List<ContentBlock> rendered = renderer.render("what is 2+2?");

      assertThat(rendered).isEqualTo(Message.user("what is 2+2?").content());
      assertThat(rendered).containsExactly(new TextBlock("what is 2+2?"));
    }
  }

  @Nested
  class The_json_renderer {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void tags_the_block_with_the_snake_case_simple_name() {
      InputRenderer<OrderEscalation> renderer = InputRenderer.json(mapper);

      TextBlock block = (TextBlock) renderer.render(new OrderEscalation("o-1", "fraud")).getFirst();

      assertThat(block.text()).startsWith("[order_escalation]\n");
    }

    @Test
    void derives_snake_case_across_more_than_two_words() {
      InputRenderer<CustomerSupportTicket> renderer = InputRenderer.json(mapper);

      TextBlock block =
          (TextBlock) renderer.render(new CustomerSupportTicket("billing")).getFirst();

      assertThat(block.text()).startsWith("[customer_support_ticket]\n");
    }

    @Test
    void the_body_is_the_mappers_canonical_json_and_round_trips() throws Exception {
      OrderEscalation input = new OrderEscalation("o-1", "fraud");
      InputRenderer<OrderEscalation> renderer = InputRenderer.json(mapper);

      TextBlock block = (TextBlock) renderer.render(input).getFirst();
      String body = block.text().substring(block.text().indexOf('\n') + 1);

      assertThat(body).isEqualTo(mapper.writeValueAsString(input));
      assertThat(mapper.readValue(body, OrderEscalation.class)).isEqualTo(input);
    }

    @Test
    void renders_as_exactly_one_content_block() {
      InputRenderer<OrderEscalation> renderer = InputRenderer.json(mapper);

      List<ContentBlock> rendered = renderer.render(new OrderEscalation("o-1", "fraud"));

      assertThat(rendered).hasSize(1);
    }

    @Test
    void a_null_mapper_is_rejected() {
      assertThatThrownBy(() -> InputRenderer.json(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("mapper");
    }
  }
}
