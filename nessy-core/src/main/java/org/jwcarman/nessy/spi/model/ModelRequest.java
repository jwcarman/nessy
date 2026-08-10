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

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.api.Context;
import org.jwcarman.nessy.api.tool.ToolSpec;

/**
 * Everything a provider needs for one call.
 *
 * <p>The system prompt is a field rather than a message because that is how the providers we target
 * actually model it.
 *
 * @param requested capabilities the harness would like used, not a guarantee any provider offers
 *     them
 * @param responseSchema a JSON Schema the model's final response should conform to, or {@code null}
 *     if the caller has no structured-output requirement. This is the second sanctioned nullable
 *     field in this codebase (see {@code SessionState.failureReason} for the first): it is a slot
 *     the providers wired today ignore entirely, reserved for structured output arriving in a later
 *     change.
 */
public record ModelRequest(
    Context context,
    String systemPrompt,
    String model,
    int maxTokens,
    List<ToolSpec> tools,
    Set<Capability> requested,
    ObjectNode responseSchema) {

  public ModelRequest {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be at least 1");
    }
    tools = List.copyOf(tools);
    requested = Set.copyOf(requested);
  }

  /** What this request asked for that the given provider cannot do. */
  public Set<Capability> unsupportedBy(Set<Capability> supported) {
    Set<Capability> missing = new LinkedHashSet<>(requested);
    missing.removeAll(supported);
    return Set.copyOf(missing);
  }
}
