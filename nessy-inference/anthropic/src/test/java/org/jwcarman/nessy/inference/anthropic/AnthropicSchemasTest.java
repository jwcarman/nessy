package org.jwcarman.nessy.inference.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.InputSchema;
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
    return AnthropicSchemas.toInputSchema(new InputSchema(json), MAPPER);
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
}
