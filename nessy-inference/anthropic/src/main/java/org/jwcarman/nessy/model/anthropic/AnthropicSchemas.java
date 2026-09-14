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

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts a {@link org.jwcarman.nessy.api.tool.Tool}'s wire-neutral {@link ObjectNode} schema into
 * the SDK's {@link Tool.InputSchema}.
 *
 * <p>{@code Tool.inputSchema()} is already a JSON Schema object built by the caller, not a Java
 * type to introspect, so there is no generation step here — only a field-by-field copy of {@code
 * properties}, {@code required}, {@code $defs}, and {@code oneOf} onto the SDK's builder. A type
 * referenced by more than one property is hoisted into {@code $defs} and pointed at with {@code
 * $ref}; drop {@code $defs} and those references resolve to nothing, so it is copied across
 * verbatim. A sealed vocabulary's schema carries its branches under {@code oneOf} instead of {@code
 * properties}; drop it and the branches vanish, leaving Anthropic an empty schema, so it is copied
 * across verbatim too.
 */
public final class AnthropicSchemas {

  private static final String PROPERTIES = "properties";
  private static final String REQUIRED = "required";
  private static final String DEFS = "$defs";
  private static final String ONE_OF = "oneOf";

  private AnthropicSchemas() {}

  public static Tool.InputSchema toInputSchema(ObjectNode schema) {
    var properties = Tool.InputSchema.Properties.builder();
    var propertyNode = schema.get(PROPERTIES);
    if (propertyNode instanceof ObjectNode propertyObject) {
      for (Map.Entry<String, JsonNode> property : propertyObject.properties()) {
        properties.putAdditionalProperty(
            property.getKey(), JsonValue.fromJsonNode(property.getValue()));
      }
    }

    var required = new ArrayList<String>();
    var requiredNode = schema.get(REQUIRED);
    if (requiredNode != null) {
      requiredNode.forEach(node -> required.add(node.asText()));
    }

    var inputSchema =
        Tool.InputSchema.builder().properties(properties.build()).required(List.copyOf(required));

    var definitions = schema.get(DEFS);
    if (definitions != null) {
      inputSchema.putAdditionalProperty(DEFS, JsonValue.fromJsonNode(definitions));
    }

    var oneOf = schema.get(ONE_OF);
    if (oneOf != null) {
      inputSchema.putAdditionalProperty(ONE_OF, JsonValue.fromJsonNode(oneOf));
    }
    return inputSchema.build();
  }
}
