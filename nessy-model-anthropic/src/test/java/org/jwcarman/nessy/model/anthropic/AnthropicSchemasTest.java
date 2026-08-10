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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class AnthropicSchemasTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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
}
