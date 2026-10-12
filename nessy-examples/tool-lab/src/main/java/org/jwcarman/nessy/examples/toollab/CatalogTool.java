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

import java.util.function.Function;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.inference.ToolOffer;
import tools.jackson.databind.JsonNode;

/**
 * One fake tool: what the model is told about it, and what it answers when called.
 *
 * @param name the tool's name, valid on every vendor
 * @param description one line saying what the tool does
 * @param schema the input schema, an object with one to four described properties
 * @param write whether the tool changes something, which is what an ambiguous task must not do
 * @param stub the deterministic answer, as JSON text, from the parsed arguments
 */
record CatalogTool(
    String name,
    String description,
    JsonSchema schema,
    boolean write,
    Function<JsonNode, String> stub) {

  /** What the model is offered for this tool. */
  ToolOffer offer() {
    return new ToolOffer(new ToolName(name), description, schema);
  }

  /** The tool's answer to these arguments. */
  String run(JsonNode arguments) {
    return stub.apply(arguments);
  }
}
