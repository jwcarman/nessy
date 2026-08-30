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
package org.jwcarman.nessy.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolBinding;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The tools one kind of agent may call, and how each is governed.
 *
 * <p>Exists to keep the capture of {@code ToolBinding<?>} in ONE place. Binding raw arguments to a
 * tool's input type, describing a call, and running it all need the tool's {@code I}, which a
 * wildcard has erased. Each is a small generic method here, so nothing above ever casts.
 *
 * <p>The tools handed to a model are the very objects it will be run against — one source, so a
 * tool cannot be advertised under one schema and executed under another.
 */
public final class ToolBindings {

  private final Map<String, ToolBinding<?>> byName = new LinkedHashMap<>();
  private final ObjectMapper mapper;

  public ToolBindings(List<ToolBinding<?>> bindings, ObjectMapper mapper) {
    Objects.requireNonNull(bindings, "bindings must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    bindings.forEach(binding -> byName.put(binding.tool().name(), binding));
  }

  /** What the model is told it can call. */
  public List<Tool<?>> tools() {
    List<Tool<?>> tools = new ArrayList<>(byName.size());
    byName.values().forEach(binding -> tools.add(binding.tool()));
    return List.copyOf(tools);
  }

  public Optional<ToolBinding<?>> binding(String toolName) {
    return Optional.ofNullable(byName.get(toolName));
  }

  /** What this call means, in words a person can consent to. */
  public String describe(ToolBinding<?> binding, JsonNode arguments) {
    return describeBound(binding, arguments);
  }

  private <I> String describeBound(ToolBinding<I> binding, JsonNode arguments) {
    return binding.describer().describe(bind(binding.tool(), arguments));
  }

  /** Whether this call may run, as the binding's own approver sees it. */
  public Awaited<ApprovalResult> approve(ToolBinding<?> binding, ApprovalRequest request) {
    return binding.approver().approve(request);
  }

  /** Run it. */
  public Awaited<ToolResult> run(ToolBinding<?> binding, JsonNode arguments, ToolContext context) {
    return runBound(binding, arguments, context);
  }

  private <I> Awaited<ToolResult> runBound(
      ToolBinding<I> binding, JsonNode arguments, ToolContext context) {
    return binding.tool().execute(bind(binding.tool(), arguments), context);
  }

  private <I> I bind(Tool<I> tool, JsonNode arguments) {
    try {
      return mapper.treeToValue(arguments, tool.inputType());
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "could not read arguments for " + tool.name() + ": " + e.getMessage(), e);
    }
  }
}
