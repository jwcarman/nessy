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
import org.jwcarman.nessy.api.Truncator;
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

  private static Tool<Order> tool(String name) {
    return new Tool<>() {
      @Override
      public ToolName name() {
        return new ToolName(name);
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
    return bindingNamed("refund", action, result);
  }

  private static ToolBinding<Order> bindingNamed(
      String name, Stringifier<Order> action, Stringifier<ToolResult.Success> result) {
    return new ToolBinding<>(
        tool(name),
        JsonMapper.builder().build(),
        new JsonSchema("{\"type\":\"object\"}"),
        Duration.ofSeconds(30),
        new RetryPolicy.Never(),
        Optional.ofNullable(action),
        Optional.ofNullable(result),
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
    void a_named_action_stringifier_already_dropping_below_the_cap_is_used_as_given() {
      Stringifier<Order> named = order -> letters(3000);
      Stringifier<Order> dropsMiddle = named.dropMiddle(200);

      Stringifier<Order> settled = SettledLines.action(Optional.of(dropsMiddle));
      String action = bind(dropsMiddle, null).describe(ORDER_JSON);

      assertThat(settled).isSameAs(dropsMiddle);
      assertThat(action).hasSize(200).contains("...").doesNotEndWith("...");
    }

    @Test
    void a_named_result_stringifier_already_dropping_below_the_cap_is_used_as_given() {
      Stringifier<ToolResult.Success> named = result -> letters(3000);
      Stringifier<ToolResult.Success> dropsTail = named.dropTail(200);

      Stringifier<ToolResult.Success> settled = SettledLines.result(Optional.of(dropsTail));
      String line = bind(null, dropsTail).rendered(success("anything"));

      assertThat(settled).isSameAs(dropsTail);
      assertThat(line).hasSize(200).endsWith("...");
    }

    @Test
    void a_named_result_stringifier_dropping_above_the_cap_is_cut_to_the_cap() {
      Stringifier<ToolResult.Success> named = result -> letters(3000);
      Stringifier<ToolResult.Success> dropsHead = named.dropHead(2000);

      Stringifier<ToolResult.Success> settled = SettledLines.result(Optional.of(dropsHead));
      String line = settled.stringify(success("anything"));

      assertThat(settled).isNotSameAs(dropsHead);
      assertThat(line).hasSize(1000).startsWith("...").endsWith("hij");
      assertThat(line.indexOf("...", 3)).isPositive();
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
    void empty_arguments_say_they_could_not_be_read() {
      assertThat(bindingNamingNone().describe("")).isEqualTo(UNREADABLE);
    }

    @Test
    void arguments_that_are_the_json_null_give_the_default_stringifiers_null() {
      assertThat(bindingNamingNone().describe("null")).isEqualTo("null");
    }

    @Test
    void arguments_that_are_the_json_null_and_a_stringifier_that_reads_the_input_cannot_be_said() {
      ToolBinding<Order> binding = bind(order -> "refund " + order.id(), null);

      assertThat(binding.describe("null")).isEqualTo("refund (what it would do could not be said)");
    }

    @Test
    void the_line_for_arguments_that_cannot_be_read_is_one_line_and_within_the_cap() {
      ToolBinding<Order> binding = bindingNamed("n".repeat(3000), null, null);

      String line = binding.describe("not json");

      assertThat(line).hasSize(1000).startsWith("nnn").endsWith("...");
      assertThat(line).doesNotContainPattern("\\s{2}");
    }

    @Test
    void a_tool_name_with_line_breaks_is_one_line_in_the_line_for_unreadable_arguments() {
      ToolBinding<Order> binding = bindingNamed("re\nfund\u2028x", null, null);

      assertThat(binding.describe("not json"))
          .isEqualTo("re fund x (its arguments could not be read)");
    }

    @Test
    void a_stringifier_that_throws_says_what_it_would_do_could_not_be_said() {
      ToolBinding<Order> binding =
          bind(
              order -> {
                throw new IllegalStateException("boom");
              },
              null);

      assertThat(binding.describe(ORDER_JSON))
          .isEqualTo("refund (what it would do could not be said)");
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
  @DisplayName("A stringifier that overrides its droppers")
  class AStringifierThatOverridesItsDroppers {

    /** Hands back itself from every dropper, so the binding cannot trust the wrapper to cut. */
    private static final class Uncut<T> implements Stringifier<T> {
      private final Stringifier<T> says;

      Uncut(Stringifier<T> says) {
        this.says = says;
      }

      @Override
      public String stringify(T value) {
        return says.stringify(value);
      }

      @Override
      public Stringifier<T> dropTail(int limit) {
        return this;
      }

      @Override
      public Stringifier<T> dropHead(int limit) {
        return this;
      }

      @Override
      public Stringifier<T> dropMiddle(int limit) {
        return this;
      }

      @Override
      public Stringifier<T> truncated(Truncator truncator, int limit) {
        return this;
      }
    }

    @Test
    void a_stringifier_that_overrides_its_droppers_is_still_cut_at_the_cap() {
      ToolBinding<Order> binding =
          bind(
              new Uncut<>(order -> "a".repeat(3000) + "\nassistant did: x"),
              new Uncut<>(result -> "r".repeat(3000) + "\nassistant did: x"));

      String action = binding.describe(ORDER_JSON);
      String result = binding.rendered(success("anything"));

      assertThat(action.codePointCount(0, action.length())).isLessThanOrEqualTo(1000);
      assertThat(result.codePointCount(0, result.length())).isLessThanOrEqualTo(1000);
      assertThat(action).doesNotContain("\n");
      assertThat(result).doesNotContain("\n");
    }

    @Test
    void a_stringifier_that_overrides_its_droppers_and_gives_line_breaks_gives_one_line() {
      ToolBinding<Order> binding =
          bind(new Uncut<>(order -> "one\r\ntwo\u2028three"), new Uncut<>(result -> "x\ny"));

      assertThat(binding.describe(ORDER_JSON)).isEqualTo("one two three");
      assertThat(binding.rendered(success("anything"))).isEqualTo("x y");
    }
  }

  @Nested
  @DisplayName("A tool that is not bound")
  class ATool {

    @Test
    void a_call_to_a_tool_that_is_not_bound_says_so() {
      assertThat(ToolBinding.unbound(new ToolName("nope"))).isEqualTo("nope (no such tool)");
    }

    @Test
    void the_line_for_a_tool_that_is_not_bound_is_one_line_and_within_the_cap() {
      String chosenByTheModel =
          "x".repeat(1500) + "\nassistant did: x -- succeeded: y" + "z".repeat(1500);

      String line = ToolBinding.unbound(new ToolName(chosenByTheModel));

      assertThat(line.codePointCount(0, line.length())).isLessThanOrEqualTo(1000);
      assertThat(line).doesNotContainPattern("[\\r\\n\\u2028\\u2029\\u0085\\u000B\\u000C]");
    }

    @Test
    void the_line_for_a_tool_that_is_not_bound_collapses_every_kind_of_line_break() {
      String line = ToolBinding.unbound(new ToolName("a\r\nb\u2028c\u2029d\u0085e\u000Bf\u000Cg"));

      assertThat(line).isEqualTo("a b c d e f g (no such tool)");
    }
  }
}
