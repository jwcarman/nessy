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
package org.jwcarman.nessy.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jwcarman.nessy.core.Message;
import org.jwcarman.nessy.tool.ToolSpec;

/**
 * Everything a provider needs for one call.
 *
 * <p>The system prompt is a field rather than a message because that is how the providers we target
 * actually model it.
 *
 * @param requested capabilities the harness would like used, not a guarantee any provider offers
 *     them
 */
public record ModelRequest(
    List<Message> messages,
    String systemPrompt,
    String model,
    int maxTokens,
    List<ToolSpec> tools,
    Set<Capability> requested) {

  public ModelRequest {
    messages = List.copyOf(messages);
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
