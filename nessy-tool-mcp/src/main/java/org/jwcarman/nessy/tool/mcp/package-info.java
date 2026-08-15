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
/**
 * Turns an MCP server's tools into nessy {@link org.jwcarman.nessy.api.tool.Tool}s.
 *
 * <p>The kernel does not change: {@link org.jwcarman.nessy.api.tool.ToolSpec} already carries a raw
 * schema, {@link org.jwcarman.nessy.api.tool.Tool#spec()} is a default method an MCP-backed tool
 * simply overrides, and the durable loop never learns a tool came from a network call rather than a
 * hand-written record. {@link org.jwcarman.nessy.tool.mcp.McpToolbox} is the one public door in:
 * connect it to a transport from the official MCP Java SDK, grant the tools it opens like any
 * other, and the model calls a remote server the same way it calls anything local.
 */
package org.jwcarman.nessy.tool.mcp;
