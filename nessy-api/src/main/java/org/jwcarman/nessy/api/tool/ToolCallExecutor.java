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

import org.jwcarman.nessy.api.Awaited;

/**
 * Runs one tool call.
 *
 * <p>Returns the same {@link ToolResult} a {@link Tool} does. There is no wider engine-only type:
 * the cases only the engine can hit — an unknown tool name, arguments that will not bind, a thrown
 * exception — are failures of the call, and {@link ToolResult.Failure} already says exactly that.
 */
public interface ToolCallExecutor {

  Awaited<ToolResult> execute(ToolCall call);
}
