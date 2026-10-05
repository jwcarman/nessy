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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.EmptyInput;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.JsonSchemaGenerator;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("A tool's input schema, checked when it is bound")
class ToolInputSchemaTest {

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Restart.class, name = "Restart"),
    @JsonSubTypes.Type(value = Shutdown.class, name = "Shutdown")
  })
  sealed interface Command permits Restart, Shutdown {}

  record Restart(String host) implements Command {}

  record Shutdown(String reason) implements Command {}

  record HostRequest(Command action) {}

  static final class Labels extends HashMap<String, String> {}

  static final class Names extends ArrayList<String> {}

  private static final JsonSchemaGenerator GENERATOR = new VictoolsJsonSchemaGenerator();

  private static <T> Tool<T> tool(String name, Class<T> inputType, String handWritten) {
    return new Tool<>() {
      @Override
      public ToolName name() {
        return new ToolName(name);
      }

      @Override
      public String description() {
        return "a tool";
      }

      @Override
      public Class<T> inputType() {
        return inputType;
      }

      @Override
      public JsonSchema inputSchema(JsonSchemaGenerator generator) {
        return handWritten == null ? generator.generate(inputType) : new JsonSchema(handWritten);
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<T> request) {
        return Awaited.ready(ToolResult.ok(new Block.Text("ok")));
      }
    };
  }

  private static <T> ToolBinding<T> bind(Tool<T> tool, JsonSchema schema) {
    return new ToolBinding<>(
        tool,
        JsonMapper.builder().build(),
        schema,
        Duration.ofSeconds(30),
        new RetryPolicy.Never(),
        Optional.empty(),
        Optional.empty(),
        List.of(),
        Approver.allow(),
        Duration.ofMinutes(10),
        new RetryPolicy.Never());
  }

  @Nested
  class AnObjectAtTheRoot {

    @Test
    void a_record_with_a_sealed_field_is_accepted() {
      Tool<HostRequest> tool = tool("host_request", HostRequest.class, null);
      JsonSchema schema = tool.inputSchema(GENERATOR);

      assertThatCode(() -> bind(tool, schema)).doesNotThrowAnyException();
    }

    @Test
    void a_map_input_is_accepted() {
      Tool<Labels> tool = tool("labels", Labels.class, null);
      JsonSchema schema = tool.inputSchema(GENERATOR);

      assertThatCode(() -> bind(tool, schema)).doesNotThrowAnyException();
    }

    @Test
    void a_hand_written_object_schema_is_accepted() {
      Tool<String> tool = tool("echo", String.class, "{\"type\":\"object\",\"properties\":{}}");
      JsonSchema schema = tool.inputSchema(GENERATOR);

      assertThatCode(() -> bind(tool, schema)).doesNotThrowAnyException();
    }
  }

  @Nested
  class AToolWithNoArguments {

    @Test
    void an_empty_input_is_offered_as_an_object_with_explicitly_empty_properties() {
      Tool<EmptyInput> tool = tool("rounds", EmptyInput.class, null);

      JsonNode schema = JsonMapper.builder().build().readTree(tool.inputSchema(GENERATOR).json());

      assertThat(schema.path("type").asString()).isEqualTo("object");
      assertThat(schema.has("properties")).isTrue();
      assertThat(schema.get("properties").isObject()).isTrue();
      assertThat(schema.get("properties").size()).isZero();
    }

    @Test
    void an_empty_input_is_accepted_when_the_tool_is_bound() {
      Tool<EmptyInput> tool = tool("rounds", EmptyInput.class, null);
      JsonSchema schema = tool.inputSchema(GENERATOR);

      assertThatCode(() -> bind(tool, schema)).doesNotThrowAnyException();
    }

    @Test
    void a_call_with_an_empty_object_reaches_the_tool_as_an_empty_input() {
      AtomicReference<EmptyInput> received = new AtomicReference<>();
      Tool<EmptyInput> recording =
          new Tool<>() {
            @Override
            public ToolName name() {
              return new ToolName("rounds");
            }

            @Override
            public String description() {
              return "a tool";
            }

            @Override
            public Class<EmptyInput> inputType() {
              return EmptyInput.class;
            }

            @Override
            public Awaited<ToolResult> call(ToolCallRequest<EmptyInput> request) {
              received.set(request.input());
              return Awaited.ready(ToolResult.ok(new Block.Text("ok")));
            }
          };
      ToolBinding<EmptyInput> binding = bind(recording, recording.inputSchema(GENERATOR));

      Awaited<ToolResult> answer =
          binding.call(
              new AgentType("watch"),
              new AgentId(UUID.randomUUID()),
              new TurnId(1),
              new CallId("c1"),
              IdempotencyKey.of(UUID.randomUUID()),
              new ToolName("rounds"),
              "{}",
              Instant.now().plusSeconds(30));

      assertThat(answer).isInstanceOf(Awaited.Ready.class);
      assertThat(received.get()).isEqualTo(new EmptyInput());
    }
  }

  @Nested
  class ARootThatIsNotAnObject {

    @Test
    void a_sealed_interface_input_is_refused_naming_the_tool() {
      Tool<Command> tool = tool("host_request", Command.class, null);
      JsonSchema schema = tool.inputSchema(GENERATOR);

      assertThatThrownBy(() -> bind(tool, schema))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "tool 'host_request': its input schema must be an object at the root, but it is"
                  + " a union (oneOf); wrap the type in an object, e.g. record"
                  + " HostRequestInput(Command value)");
    }

    @Test
    void a_hand_written_string_schema_is_refused() {
      Tool<String> tool = tool("echo", String.class, "{\"type\":\"string\"}");
      JsonSchema schema = tool.inputSchema(GENERATOR);

      assertThatThrownBy(() -> bind(tool, schema))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("tool 'echo'")
          .hasMessageContaining("but it is type 'string'");
    }

    @Test
    void a_hand_written_any_of_root_is_refused() {
      Tool<String> tool = tool("either", String.class, "{\"anyOf\":[{\"type\":\"string\"}]}");
      JsonSchema schema = tool.inputSchema(GENERATOR);

      assertThatThrownBy(() -> bind(tool, schema))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("but it is a union (anyOf)");
    }

    @Test
    void a_schema_with_no_type_is_refused() {
      Tool<String> tool = tool("bare", String.class, "{}");
      JsonSchema schema = tool.inputSchema(GENERATOR);

      assertThatThrownBy(() -> bind(tool, schema))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("but it is no type at all");
    }

    @Test
    void a_list_input_is_refused() {
      Tool<Names> tool = tool("names", Names.class, null);
      JsonSchema schema = tool.inputSchema(GENERATOR);

      assertThatThrownBy(() -> bind(tool, schema))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("tool 'names'");
    }
  }
}
