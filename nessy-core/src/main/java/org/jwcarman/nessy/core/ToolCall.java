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
package org.jwcarman.nessy.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * The model's request to run one tool.
 *
 * @param id provider-assigned; the tool result must quote it back
 * @param name the tool's registered name
 * @param arguments raw JSON, not yet bound to the tool's input record
 */
public record ToolCall(String id, String name, JsonNode arguments) {

  public ToolCall {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("tool call id must not be blank");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("tool name must not be blank");
    }
    Objects.requireNonNull(arguments, "arguments must not be null");
  }
}
