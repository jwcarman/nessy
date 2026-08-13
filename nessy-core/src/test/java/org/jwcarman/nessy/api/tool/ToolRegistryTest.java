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
package org.jwcarman.nessy.api.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.internal.ToolInvoker;

class ToolRegistryTest {

  record Greet(String name) {}

  static final class GreetTool implements Tool<Greet> {
    @Override
    public String name() {
      return "greet";
    }

    @Override
    public String description() {
      return "Greets somebody by name";
    }

    @Override
    public Class<Greet> inputType() {
      return Greet.class;
    }

    @Override
    public String describe(Greet input) {
      return "greet(" + input.name() + ")";
    }

    @Override
    public Awaited<ToolResult> execute(Greet input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("Hello, " + input.name()));
    }
  }

  record Named(String value) {}

  static final class NamedTool implements Tool<Named> {
    private final String name;

    NamedTool(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String description() {
      return "A tool named " + name;
    }

    @Override
    public Class<Named> inputType() {
      return Named.class;
    }

    @Override
    public Awaited<ToolResult> execute(Named input, ToolContext context) {
      return Awaited.ready(ToolResult.ok(input.value()));
    }
  }

  private final ToolRegistry registry = ToolRegistry.of(new GreetTool());
  private final ToolInvoker invoker = new ToolInvoker(new ObjectMapper());

  private static ToolCall greetCall(String name) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.put("name", name);
    return new ToolCall("c1", "greet", args);
  }

  @Nested
  class Lookup {

    @Test
    void finds_a_registered_tool() {
      assertThat(registry.find("greet")).isPresent();
    }

    @Test
    void the_interface_is_the_front_door_to_its_default() {
      ToolRegistry localRegistry = ToolRegistry.of(new GreetTool());

      assertThat(localRegistry.find("greet")).isPresent();
    }

    @Test
    void returns_empty_for_an_unknown_tool() {
      assertThat(registry.find("nope")).isEmpty();
    }
  }

  @Nested
  class Specs {

    @Test
    void specs_carry_name_description_and_schema() {
      ToolSpec spec = registry.specs().getFirst();

      assertThat(spec.name()).isEqualTo("greet");
      assertThat(spec.description()).isEqualTo("Greets somebody by name");
      assertThat(spec.inputSchema().get("properties").has("name")).isTrue();
    }

    @Test
    void specs_preserve_registration_order() {
      ToolRegistry ordered =
          ToolRegistry.of(new NamedTool("charlie"), new NamedTool("alpha"), new NamedTool("bravo"));

      List<String> names = ordered.specs().stream().map(ToolSpec::name).toList();

      assertThat(names).containsExactly("charlie", "alpha", "bravo");
    }

    @Test
    void duplicate_names_are_rejected_at_registration_time() {
      GreetTool first = new GreetTool();
      GreetTool second = new GreetTool();

      assertThatThrownBy(() -> ToolRegistry.of(first, second))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("greet");
    }
  }

  @Nested
  class Invocation {

    @Test
    void invoking_binds_json_arguments_to_the_record() {
      Tool<?> tool = registry.find("greet").orElseThrow();
      ToolCall call = greetCall("Ada");

      Awaited<ToolResult> awaited =
          invoker.invoke(
              tool, call, new ToolContext(new ConversationId("s1"), call, EventEmitter.noop()));

      assertThat(awaited).isEqualTo(Awaited.ready(ToolResult.ok("Hello, Ada")));
    }

    @Test
    void describe_renders_the_call_for_a_human() {
      Tool<?> tool = registry.find("greet").orElseThrow();

      assertThat(invoker.describe(tool, greetCall("Ada"))).isEqualTo("greet(Ada)");
    }
  }
}
