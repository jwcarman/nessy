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
package org.jwcarman.nessy.model.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.Schemas;

class AnthropicSchemasTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Restart.class, name = "Restart"),
    @JsonSubTypes.Type(value = Shutdown.class, name = "Shutdown")
  })
  sealed interface Vocabulary permits Restart, Shutdown {}

  record Restart(String host) implements Vocabulary {}

  record Shutdown(String reason) implements Vocabulary {}

  /**
   * A sealed ABSTRACT CLASS (not an interface): {@code Schemas.of} does not route this through its
   * dedicated sealed-interface path (gated on {@code isInterface()}), but victools' Jackson module
   * still derives {@code anyOf} for it purely from the annotations — {@code Schemas.of}'s {@code
   * anyOf}→{@code oneOf} normalization has to apply on that plain-generation path too, or this
   * adapter (which only copies a top-level {@code oneOf} key) silently drops every branch, handing
   * Anthropic an empty schema.
   */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = ClassRestart.class, name = "ClassRestart"),
    @JsonSubTypes.Type(value = ClassShutdown.class, name = "ClassShutdown")
  })
  abstract static sealed class ClassVocabulary permits ClassRestart, ClassShutdown {}

  static final class ClassRestart extends ClassVocabulary {
    private String host;

    public String getHost() {
      return host;
    }

    public void setHost(String host) {
      this.host = host;
    }
  }

  static final class ClassShutdown extends ClassVocabulary {
    private String reason;

    public String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }
  }

  @Test
  void properties_are_copied_onto_the_input_schema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("path").put("type", "string");

    var inputSchema = AnthropicSchemas.toInputSchema(schema);

    var jsonProperties = inputSchema.properties().orElseThrow()._additionalProperties();
    assertThat(jsonProperties).containsKey("path");
    assertThat(jsonProperties.get("path").convert(JsonNode.class).get("type").asText())
        .isEqualTo("string");
  }

  @Test
  void required_names_are_copied_in_order() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.putObject("properties");
    var required = schema.putArray("required");
    required.add("path");
    required.add("maxLines");

    var inputSchema = AnthropicSchemas.toInputSchema(schema);

    assertThat(inputSchema.required().orElseThrow()).containsExactly("path", "maxLines");
  }

  @Test
  void a_missing_properties_field_produces_no_additional_properties() {
    // schema.get("properties") returns null when the schema has no "properties" field at all;
    // toInputSchema must not blow up on that, and must simply carry no properties across.
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");

    var inputSchema = AnthropicSchemas.toInputSchema(schema);

    assertThat(inputSchema.properties().orElseThrow()._additionalProperties()).isEmpty();
  }

  @Test
  void a_missing_required_array_produces_an_empty_list() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.putObject("properties");

    var inputSchema = AnthropicSchemas.toInputSchema(schema);

    assertThat(inputSchema.required().orElseThrow()).isEmpty();
  }

  @Test
  void defs_are_hoisted_onto_the_input_schema_unchanged() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.putObject("properties").putObject("target").put("$ref", "#/$defs/Target");
    ObjectNode defs = schema.putObject("$defs");
    defs.putObject("Target").put("type", "string");

    var inputSchema = AnthropicSchemas.toInputSchema(schema);

    var additionalProperties = inputSchema._additionalProperties();
    assertThat(additionalProperties).containsKey("$defs");
    assertThat(
            additionalProperties
                .get("$defs")
                .convert(JsonNode.class)
                .get("Target")
                .get("type")
                .asText())
        .isEqualTo("string");
  }

  @Test
  void absent_defs_are_not_added() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.putObject("properties");

    var inputSchema = AnthropicSchemas.toInputSchema(schema);

    assertThat(inputSchema._additionalProperties()).doesNotContainKey("$defs");
  }

  @Test
  void a_sealed_vocabularys_oneOf_branches_survive_adaptation() {
    ObjectNode schema = Schemas.of(Vocabulary.class);
    StubTool spec = new StubTool("restart_or_shutdown", "Restarts or shuts down a host", schema);

    var inputSchema = AnthropicSchemas.toInputSchema(spec.inputSchema());

    var additionalProperties = inputSchema._additionalProperties();
    assertThat(additionalProperties).containsKey("oneOf");
    JsonNode oneOf = additionalProperties.get("oneOf").convert(JsonNode.class);
    assertThat(oneOf).hasSize(2);

    var typeConsts = new ArrayList<String>();
    oneOf.forEach(branch -> typeConsts.add(branch.at("/properties/type/const").asText()));
    assertThat(typeConsts).containsExactlyInAnyOrder("Restart", "Shutdown");
  }

  /**
   * The reviewer's case: an annotated sealed ABSTRACT CLASS reaches {@code Schemas.of}'s plain
   * (non-sealed-interface) generation branch, where victools still emits {@code anyOf} straight
   * from the Jackson annotations. Without {@code Schemas.of}'s normalization, this adapter — which
   * only ever looks for {@code oneOf} — would silently produce zero branches (an empty schema
   * handed to the model) instead of failing loudly; this pins that it does not.
   */
  @Test
  void a_sealed_abstract_classs_oneOf_branches_survive_adaptation_too() {
    ObjectNode schema = Schemas.of(ClassVocabulary.class);
    StubTool spec = new StubTool("restart_or_shutdown", "Restarts or shuts down a host", schema);

    var inputSchema = AnthropicSchemas.toInputSchema(spec.inputSchema());

    var additionalProperties = inputSchema._additionalProperties();
    assertThat(additionalProperties).containsKey("oneOf");
    JsonNode oneOf = additionalProperties.get("oneOf").convert(JsonNode.class);
    assertThat(oneOf).hasSize(2);

    var typeConsts = new ArrayList<String>();
    oneOf.forEach(branch -> typeConsts.add(branch.at("/properties/type/const").asText()));
    assertThat(typeConsts).containsExactlyInAnyOrder("ClassRestart", "ClassShutdown");
  }
}
