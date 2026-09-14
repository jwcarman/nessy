package org.jwcarman.nessy.inference.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.api.tool.InputSchema;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tool's schema, as Anthropic wants one.
 *
 * <p>Its own class because this wire does not take a schema document whole. {@code properties} and
 * {@code required} are named fields, and anything else a generated schema carries has to be put
 * back beside them by hand -- which is easy to get subtly wrong and worth testing on its own.
 */
public final class AnthropicSchemas {

  private static final String PROPERTIES = "properties";
  private static final String REQUIRED = "required";

  /**
   * Everything else the generator emits that a model still needs.
   *
   * <p>{@code $defs} and {@code oneOf} are how a sealed input type is described -- a tool whose
   * argument is one of several shapes is nothing but a {@code oneOf} over {@code $defs}. Dropping
   * them leaves the model an object with no properties and no way to know what to write.
   */
  private static final List<String> CARRIED_THROUGH = List.of("$defs", "oneOf");

  private AnthropicSchemas() {}

  /**
   * Read into plain maps and lists rather than into nodes, because the two sides are on different
   * Jackson majors: this project is on Jackson 3 ({@code tools.jackson}) while the SDK's {@code
   * JsonValue.fromJsonNode} wants a Jackson 2 node. {@code JsonValue.from(Object)} is the bridge
   * that needs neither to know about the other.
   */
  public static Tool.InputSchema toInputSchema(InputSchema schema, JsonMapper mapper) {
    Map<String, Object> document = mapper.readValue(schema.json(), new TypeReference<>() {});

    Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
    if (document.get(PROPERTIES) instanceof Map<?, ?> declared) {
      declared.forEach(
          (name, value) ->
              properties.putAdditionalProperty(String.valueOf(name), JsonValue.from(value)));
    }

    List<String> required = new ArrayList<>();
    if (document.get(REQUIRED) instanceof List<?> names) {
      names.forEach(name -> required.add(String.valueOf(name)));
    }

    Tool.InputSchema.Builder input =
        Tool.InputSchema.builder().properties(properties.build()).required(List.copyOf(required));
    for (String key : CARRIED_THROUGH) {
      Object value = document.get(key);
      if (value != null) {
        input.putAdditionalProperty(key, JsonValue.from(value));
      }
    }
    return input.build();
  }
}
