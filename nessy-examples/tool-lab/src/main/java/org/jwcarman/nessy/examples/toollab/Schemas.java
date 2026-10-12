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
package org.jwcarman.nessy.examples.toollab;

import org.jwcarman.nessy.api.JsonSchema;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Builds the small object schemas the lab's tools take. */
final class Schemas {

  static final JsonMapper MAPPER = JsonMapper.builder().build();

  private Schemas() {}

  /**
   * One property of a tool's input.
   *
   * @param name the property's name
   * @param type the JSON schema type: {@code string}, {@code integer} or {@code array}
   * @param description what the property is, for the model
   * @param required whether the model must supply it
   */
  record Prop(String name, String type, String description, boolean required) {

    static Prop required(String name, String type, String description) {
      return new Prop(name, type, description, true);
    }

    static Prop optional(String name, String type, String description) {
      return new Prop(name, type, description, false);
    }
  }

  /** An object schema with these properties; an {@code array} property holds strings. */
  static JsonSchema object(Prop... props) {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    ArrayNode required = schema.putArray("required");
    for (Prop prop : props) {
      ObjectNode property = properties.putObject(prop.name());
      property.put("type", prop.type());
      if (prop.type().equals("array")) {
        property.putObject("items").put("type", "string");
      }
      property.put("description", prop.description());
      if (prop.required()) {
        required.add(prop.name());
      }
    }
    return new JsonSchema(MAPPER.writeValueAsString(schema));
  }
}
