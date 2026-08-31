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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jwcarman.nessy.api.Awaited;

/**
 * Something the model can call.
 *
 * <p>A tool declares what it is called, what it does, and the shape of its input — and that is the
 * only description of it that exists. There is no separate spec type to drift out of step, so a
 * tool cannot be offered under one schema and executed under another.
 *
 * <p><b>What a tool may say.</b> It returns its own answer, ready or deferred — succeeded with
 * content, or failed with an explanation. The one thing it does not get to author is the id of the
 * call it is answering; see {@link ToolResult}.
 *
 * @param <I> the input this tool binds its arguments to
 */
public interface Tool<I> {

  Class<I> inputType();

  /**
   * The shape of this tool's arguments, as the model is offered them.
   *
   * <p>Generated from {@link #inputType()} by default, because a tool that has already declared a
   * Java type has already said what its arguments are, and writing the same thing again in JSON is
   * how the two drift apart. Annotate the input's components with {@code @JsonPropertyDescription}
   * to tell the model what each one means — that is worth doing, and it is the only part a
   * generator cannot infer.
   *
   * <p>Override when the schema is not derivable from a Java type: an MCP tool's shape is known
   * only to the server it came from, and a tool whose arguments need constraints a record cannot
   * express (an enum of allowed values, a numeric range) says so here.
   */
  default ObjectNode inputSchema() {
    return Schemas.of(inputType());
  }

  String name();

  String description();

  /**
   * Runs, or says it will answer later.
   *
   * <p>{@link Awaited.Deferred} hands the wait back to the engine: the tool has already told the
   * outside world where to answer, using {@link ToolContext#replyToken()}, and states how long the
   * question should stand.
   *
   * @param input the call's arguments, already bound to {@link #inputType()}
   * @param context what else this call offers — today, where to send a deferred answer
   */
  Awaited<ToolResult> execute(I input, ToolContext context);
}
