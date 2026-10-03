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
package org.jwcarman.nessy.inference.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.JsonSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A generated schema, reshaped into what this wire takes.
 *
 * <p><b>Its own class because this wire does not take a document whole.</b> {@code properties} and
 * {@code required} are named fields and everything else has to be put back beside them by hand,
 * which is exactly the kind of translation that fails silently: a dropped key produces a schema the
 * vendor accepts and the model cannot write against.
 *
 * <p>Driven from literal schema documents rather than from the generator. What this class owes is
 * "given a document with these keys, produce this input schema" -- whether the generator emits
 * those keys is the generator's own test, and whether the pair actually satisfies Anthropic is
 * something only {@code AnthropicLiveTest} can say.
 */
class AnthropicSchemasTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static Tool.InputSchema adapt(String json) {
    return AnthropicSchemas.toInputSchema(new JsonSchema(json), MAPPER);
  }

  @Test
  void properties_are_copied_across() {
    var schema =
        adapt(
            """
            {"type":"object","properties":{"path":{"type":"string"},\
            "maxLines":{"type":"integer"}}}""");

    Map<String, JsonValue> properties = schema.properties().orElseThrow()._additionalProperties();
    assertThat(properties).containsKeys("path", "maxLines");
    assertThat(properties.get("path").convert(Object.class)).isEqualTo(Map.of("type", "string"));
  }

  @Test
  void required_names_are_copied_in_order() {
    var schema =
        adapt(
            """
            {"type":"object","properties":{"path":{"type":"string"},\
            "maxLines":{"type":"integer"}},"required":["path","maxLines"]}""");

    assertThat(schema.required().orElseThrow()).containsExactly("path", "maxLines");
  }

  @Test
  void a_document_with_no_properties_produces_none() {
    assertThat(adapt("{\"type\":\"object\"}").properties().orElseThrow()._additionalProperties())
        .isEmpty();
  }

  @Test
  void a_document_with_no_required_array_produces_an_empty_list() {
    assertThat(adapt("{\"type\":\"object\",\"properties\":{}}").required().orElseThrow()).isEmpty();
  }

  /**
   * {@code $defs} and {@code oneOf} are how a sealed input type is described -- a tool whose
   * argument is one of several shapes is nothing but a {@code oneOf} over {@code $defs}. Dropping
   * them leaves the model an object with no properties and no way to know what to write, which is a
   * schema the vendor accepts and no model can satisfy.
   */
  @Test
  void the_shape_of_a_sealed_vocabulary_survives_adaptation() {
    var schema =
        adapt(
            """
            {"type":"object","properties":{},\
            "$defs":{"Restart":{"type":"object","properties":{"host":{"type":"string"}}},\
            "Shutdown":{"type":"object","properties":{"reason":{"type":"string"}}}},\
            "oneOf":[{"$ref":"#/$defs/Restart"},{"$ref":"#/$defs/Shutdown"}]}""");

    Map<String, JsonValue> carried = schema._additionalProperties();
    assertThat(carried).containsKeys("$defs", "oneOf");
    assertThat(carried.get("$defs").convert(Object.class))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsKeys("Restart", "Shutdown");
    assertThat(carried.get("oneOf").convert(Object.class))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .hasSize(2);
  }

  /** Absent keys are absent, not present-and-null, which this wire reads differently. */
  @Test
  void what_a_document_does_not_carry_is_not_invented() {
    var schema = adapt("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");

    assertThat(schema._additionalProperties()).doesNotContainKeys("$defs", "oneOf");
  }

  /** Nested definitions go through untouched; only the top level is rearranged. */
  @Test
  void nested_content_is_carried_verbatim_rather_than_rebuilt() {
    var schema =
        adapt(
            """
            {"type":"object","properties":{},\
            "$defs":{"Deep":{"type":"object","properties":{\
            "inner":{"type":"array","items":{"type":"string"}}}}}}""");

    assertThat(schema._additionalProperties().get("$defs").convert(Object.class))
        .isEqualTo(
            Map.of(
                "Deep",
                Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("inner", Map.of("type", "array", "items", Map.of("type", "string"))))));
    assertThat(List.of()).isEmpty();
  }

  /**
   * An answer's schema as the generator writes one (PLAIN_JSON: no additionalProperties anywhere),
   * with an object inline, objects in an array, and a shared object under $defs.
   */
  private static final String GENERATED_ANSWER =
      """
      {"$schema":"https://json-schema.org/draft/2020-12/schema",\
      "$defs":{"Offer":{"type":"object","properties":{"kind":{"type":"string"}}}},\
      "type":"object",\
      "properties":{"where":{"type":"object","properties":{"city":{"type":"string"}}},\
      "offers":{"type":"array","items":{"$ref":"#/$defs/Offer"}},\
      "lines":{"type":"array","items":{"type":"object","properties":{"n":{"type":"integer"}}}},\
      "tags":{"type":"array","items":{"type":"string"}}}}""";

  private static JsonNode answerFor(String schema) {
    return MAPPER.valueToTree(AnthropicSchemas.forAnswer(new JsonSchema(schema), MAPPER));
  }

  @Test
  void every_object_in_an_answer_schema_closes_its_properties() {
    JsonNode answer = answerFor(GENERATED_ANSWER);

    assertThat(answer.at("/additionalProperties").isBoolean()).isTrue();
    assertThat(answer.at("/additionalProperties").asBoolean()).isFalse();
    assertThat(answer.at("/properties/where/additionalProperties").isBoolean()).isTrue();
    assertThat(answer.at("/properties/lines/items/additionalProperties").isBoolean()).isTrue();
    assertThat(answer.at("/$defs/Offer/additionalProperties").isBoolean()).isTrue();
  }

  @Test
  void a_schema_that_is_not_an_object_is_left_alone() {
    JsonNode answer = answerFor(GENERATED_ANSWER);

    assertThat(answer.at("/properties/tags").has("additionalProperties")).isFalse();
    assertThat(answer.at("/properties/tags/items").has("additionalProperties")).isFalse();
    assertThat(answer.at("/properties/offers/items").has("additionalProperties")).isFalse();
  }

  @Test
  void an_object_that_already_says_what_it_allows_is_left_as_written() {
    JsonNode answer =
        answerFor(
            """
            {"type":"object","properties":{"a":{"type":"string"}},\
            "additionalProperties":{"type":"string"}}""");

    assertThat(answer.at("/additionalProperties/type").asString()).isEqualTo("string");
  }
}
