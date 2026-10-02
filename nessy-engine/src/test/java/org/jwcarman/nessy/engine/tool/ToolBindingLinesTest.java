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
package org.jwcarman.nessy.engine.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Stringifier;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("The lines a tool's binding records for a call")
class ToolBindingLinesTest {

  record Order(String id, int cents) {}

  private static final String ORDER_JSON = "{\"id\":\"ord_88\",\"cents\":4200}";

  private static Tool<Order> tool() {
    return new Tool<>() {
      @Override
      public ToolName name() {
        return new ToolName("refund");
      }

      @Override
      public String description() {
        return "refunds an order";
      }

      @Override
      public Class<Order> inputType() {
        return Order.class;
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Order> request) {
        return Awaited.ready(ToolResult.ok(new Block.Text("ok")));
      }
    };
  }

  private static ToolBinding<Order> bind(
      Stringifier<Order> action, Stringifier<ToolResult.Success> result) {
    return new ToolBinding<>(
        tool(),
        JsonMapper.builder().build(),
        new JsonSchema("{\"type\":\"object\"}"),
        Duration.ofSeconds(30),
        new RetryPolicy.Never(),
        SettledLines.action(Optional.ofNullable(action)),
        SettledLines.result(Optional.ofNullable(result)),
        List.of(),
        Approver.allow(),
        Duration.ofMinutes(10),
        new RetryPolicy.Never());
  }

  private static ToolBinding<Order> bindingNamingNone() {
    return bind(null, null);
  }

  private static ToolResult.Success success(String text) {
    return new ToolResult.Success(List.of(new Block.Text(text)));
  }

  private static String letters(int count) {
    return "abcdefghij".repeat(count / 10 + 1).substring(0, count);
  }

  @Nested
  @DisplayName("With no stringifier named")
  class WithNoStringifierNamed {

    @Test
    void
        with_no_stringifier_named_an_action_is_the_inputs_to_string_cut_at_255_keeping_its_start() {
      ToolBinding<Order> binding = bindingNamingNone();
      String arguments = "{\"id\":\"" + letters(400) + "\",\"cents\":1}";

      String line = binding.describe(arguments);

      assertThat(line).hasSize(255).startsWith("Order[id=abcdefghij").endsWith("...");
    }

    @Test
    void a_short_action_is_the_inputs_to_string_as_it_is() {
      assertThat(bindingNamingNone().describe(ORDER_JSON))
          .isEqualTo("Order[id=ord_88, cents=4200]");
    }

    @Test
    void with_no_stringifier_named_a_result_is_its_text_cut_at_255_dropping_its_middle() {
      ToolBinding<Order> binding = bindingNamingNone();

      String line = binding.rendered(success(letters(400)));

      assertThat(line).hasSize(255).startsWith("abcdefghij").contains("...").endsWith("hij");
      assertThat(line).doesNotEndWith("...");
    }

    @Test
    void a_result_of_several_text_blocks_is_their_text_joined_by_a_space() {
      ToolResult.Success result =
          new ToolResult.Success(List.of(new Block.Text("one"), new Block.Text("two")));

      assertThat(bindingNamingNone().rendered(result)).isEqualTo("one two");
    }
  }

  @Nested
  @DisplayName("With a stringifier named")
  class WithAStringifierNamed {

    @Test
    void a_named_stringifier_is_cut_at_1000() {
      ToolBinding<Order> binding = bind(order -> letters(3000), result -> letters(3000));

      String action = binding.describe(ORDER_JSON);
      String result = binding.rendered(success("anything"));

      assertThat(action).hasSize(1000).endsWith("...");
      assertThat(result).hasSize(1000).contains("...").doesNotEndWith("...");
    }

    @Test
    void a_named_stringifier_already_dropping_below_the_cap_is_used_as_given() {
      ToolBinding<Order> binding = bind(order -> letters(3000), result -> "unused");
      ToolBinding<Order> middle = bind(order -> letters(3000), null);
      Stringifier<Order> dropsMiddle = order -> letters(3000);
      ToolBinding<Order> given = bind(dropsMiddle.dropMiddle(200), null);

      String action = given.describe(ORDER_JSON);

      assertThat(binding.describe(ORDER_JSON)).hasSize(1000);
      assertThat(middle.describe(ORDER_JSON)).hasSize(1000);
      assertThat(action).hasSize(200).contains("...").doesNotEndWith("...");
    }

    @Test
    void a_named_stringifier_dropping_above_the_cap_is_cut_to_the_cap() {
      Stringifier<Order> dropsMiddle = order -> letters(3000);
      ToolBinding<Order> binding = bind(dropsMiddle.dropMiddle(2000), null);

      String action = binding.describe(ORDER_JSON);

      assertThat(action).hasSize(1000).endsWith("...");
    }
  }

  @Nested
  @DisplayName("Describing a call")
  class DescribingACall {

    private static final String UNREADABLE = "refund (its arguments could not be read)";

    @Test
    void text_the_stringifier_gives_is_the_action() {
      ToolBinding<Order> binding = bind(order -> "refund " + order.id(), null);

      assertThat(binding.describe(ORDER_JSON)).isEqualTo("refund ord_88");
    }

    @Test
    void a_stringifier_that_gives_null_leaves_the_tool_name() {
      ToolBinding<Order> binding = bind(order -> null, null);

      assertThat(binding.describe(ORDER_JSON)).isEqualTo("refund");
    }

    @Test
    void a_stringifier_that_gives_blank_leaves_the_tool_name() {
      ToolBinding<Order> binding = bind(order -> " \n\t ", null);

      assertThat(binding.describe(ORDER_JSON)).isEqualTo("refund");
    }

    @Test
    void arguments_that_are_not_json_say_they_could_not_be_read() {
      assertThat(bindingNamingNone().describe("not json")).isEqualTo(UNREADABLE);
    }

    @Test
    void arguments_of_the_wrong_shape_say_they_could_not_be_read() {
      assertThat(bindingNamingNone().describe("{\"id\":\"ord_88\",\"cents\":\"many\"}"))
          .isEqualTo(UNREADABLE);
    }

    @Test
    void a_stringifier_that_throws_says_the_arguments_could_not_be_read() {
      ToolBinding<Order> binding =
          bind(
              order -> {
                throw new IllegalStateException("boom");
              },
              null);

      assertThat(binding.describe(ORDER_JSON)).isEqualTo(UNREADABLE);
    }
  }

  @Nested
  @DisplayName("Rendering a result")
  class RenderingAResult {

    @Test
    void a_stringifier_that_throws_gives_an_empty_line() {
      ToolBinding<Order> binding =
          bind(
              null,
              result -> {
                throw new IllegalStateException("boom");
              });

      assertThat(binding.rendered(success("fine"))).isEmpty();
    }

    @Test
    void a_stringifier_that_gives_null_gives_an_empty_line() {
      ToolBinding<Order> binding = bind(null, result -> null);

      assertThat(binding.rendered(success("fine"))).isEmpty();
    }
  }

  @Nested
  @DisplayName("A tool that is not bound")
  class ATool {

    @Test
    void a_call_to_a_tool_that_is_not_bound_says_so() {
      assertThat(ToolBinding.unbound(new ToolName("nope"))).isEqualTo("nope (no such tool)");
    }
  }
}
