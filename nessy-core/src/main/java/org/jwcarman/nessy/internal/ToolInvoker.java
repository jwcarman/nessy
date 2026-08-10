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
package org.jwcarman.nessy.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * Binds a call's JSON arguments to a tool's input record and runs it.
 *
 * <p>Exists as its own type because {@code Tool<?>} cannot be invoked directly — the wildcard has
 * to be captured by a type variable first, which is what the private helpers here do.
 */
public final class ToolInvoker {

  private final ObjectMapper mapper;

  public ToolInvoker(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public Awaited<ToolResult> invoke(Tool<?> tool, ToolCall call, ToolContext context) {
    return invokeCaptured(tool, call, context);
  }

  public String describe(Tool<?> tool, ToolCall call) {
    return describeCaptured(tool, call);
  }

  private <T> Awaited<ToolResult> invokeCaptured(Tool<T> tool, ToolCall call, ToolContext context) {
    return tool.execute(bind(tool, call), context);
  }

  private <T> String describeCaptured(Tool<T> tool, ToolCall call) {
    return tool.describe(bind(tool, call));
  }

  private <T> T bind(Tool<T> tool, ToolCall call) {
    return mapper.convertValue(call.arguments(), tool.inputType());
  }
}
