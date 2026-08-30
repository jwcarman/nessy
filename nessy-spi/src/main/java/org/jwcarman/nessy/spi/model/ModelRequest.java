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
package org.jwcarman.nessy.spi.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.tool.Tool;

/**
 * Everything a provider needs for one call.
 *
 * <p>The system prompt is a field rather than a message because that is how the providers we target
 * actually model it — and because it is a property of the KIND of agent, not something the
 * conversation said.
 *
 * <p><b>Tools are passed as {@link Tool}s rather than as a parallel declaration type.</b> A tool
 * already carries its name, its description, and its input schema, and making that the single
 * source means a tool cannot be advertised under one schema and run under another. A provider reads
 * those three and ignores the rest.
 *
 * @param context the conversation so far, already wire-safe: every tool call in it is answered
 * @param systemPrompt the standing instruction every turn carries
 * @param maxTokens the longest answer to allow
 * @param tools what the model may ask for, possibly empty
 * @param requested capabilities the caller would LIKE used — not a guarantee any provider offers
 *     them, and never something to branch on: an adapter that cannot oblige simply does not
 */
public record ModelRequest(
    Context context,
    String systemPrompt,
    int maxTokens,
    List<Tool<?>> tools,
    Set<Capability> requested) {

  public ModelRequest {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be at least 1");
    }
    tools = List.copyOf(tools);
    requested = Set.copyOf(requested);
  }
}
