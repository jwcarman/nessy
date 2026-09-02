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
package org.jwcarman.nessy.api.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.jwcarman.nessy.api.CallId;

/**
 * One request from the model to run a tool: which call, which tool, and with what.
 *
 * <p>The non-wire twin of {@code ToolCallBlock}, exactly as {@link ToolResult} is of {@code
 * ToolResultBlock}. {@code id} is the model's own identifier for this call and is what the answer
 * must be paired to — which is why a tool never authors it.
 *
 * <p><b>This is the boundary a provider's call id crosses.</b> {@link CallId} checks it here, in
 * the adapter that read it off the wire, rather than letting it travel on to a primary key and fail
 * in an INSERT that names none of this.
 *
 * @param id the model's identifier for this call
 * @param name which tool
 * @param arguments the raw arguments, as the model produced them, before binding to a tool's input
 *     type
 */
public record ToolCall(CallId id, String name, JsonNode arguments) {

  public ToolCall {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(arguments, "arguments must not be null");
  }
}
