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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolEventListener;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.api.tool.approval.Approver;

/**
 * {@link AgentTools} over the real thing: a list of {@link ToolGrant}s, each a {@link Tool} paired
 * with the {@link Approver} that decides whether it may run.
 *
 * <p>This is the seam {@code AgentTools} existed to hold open. Before it, a host had to hand the
 * engine a bespoke catalog — which is why the watchman's shell commands were the only tools that
 * ever worked. Anything built with {@code Tool.of(...)} now runs on this engine.
 *
 * <p>Two things it deliberately does NOT do. It does not decide approvals — {@code needsApproval}
 * only reports whether a grant has an approver that can say no, and the answer itself comes from
 * the approval actor at the moment of the call. And it does not defer: a tool returning {@code
 * Awaited.Deferred} is reported as an error here, because deferral is routed by the engine
 * (composition spec §8), not resolved inside a catalog lookup.
 */
public final class GrantedTools implements AgentTools {

  private final Map<String, ToolGrant> byName;
  private final List<ToolSpec> specs;
  private final ObjectMapper mapper;

  public GrantedTools(List<ToolGrant> grants, ObjectMapper mapper) {
    Objects.requireNonNull(grants, "grants must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    Map<String, ToolGrant> named = new LinkedHashMap<>();
    for (ToolGrant grant : grants) {
      String name = grant.tool().name();
      if (named.putIfAbsent(name, grant) != null) {
        throw new IllegalArgumentException("two tools are called '%s'".formatted(name));
      }
    }
    this.byName = Map.copyOf(named);
    this.specs = grants.stream().map(grant -> grant.tool().spec()).toList();
  }

  @Override
  public List<ToolSpec> specs() {
    return specs;
  }

  @Override
  public boolean needsApproval(String tool) {
    ToolGrant grant = byName.get(tool);
    return grant != null && grant.approver() != null;
  }

  @Override
  public String action(String tool, String argumentsJson) {
    ToolGrant grant = byName.get(tool);
    if (grant == null) {
      return "unknown tool: " + tool;
    }
    return "%s %s".formatted(grant.tool().name(), argumentsJson);
  }

  @Override
  public String run(String tool, String argumentsJson) {
    ToolGrant grant = byName.get(tool);
    if (grant == null) {
      return "no such tool: " + tool;
    }
    return execute(grant.tool(), argumentsJson).text();
  }

  @Override
  public JsonNode argumentsOf(String argumentsJson) {
    try {
      return mapper.readTree(
          argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("malformed tool arguments: " + e.getOriginalMessage(), e);
    }
  }

  private <T> ToolResult execute(Tool<T> tool, String argumentsJson) {
    T input;
    try {
      input =
          mapper.readValue(
              argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson,
              tool.inputType());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return ToolResult.error(
          "could not read arguments for " + tool.name() + ": " + e.getOriginalMessage());
    }
    ToolContext context =
        new ToolContext(
            new ToolCall(tool.name(), tool.name(), mapper.createObjectNode()),
            ToolEventListener.noop(),
            ComputationId.of(tool.name()));
    Awaited<ToolResult> awaited = tool.execute(input, context);
    return switch (awaited) {
      case Awaited.Ready<ToolResult> ready -> ready.value();
      case Awaited.Deferred<ToolResult> deferred ->
          ToolResult.error(
              tool.name()
                  + " deferred, which this path cannot route yet; a deferred answer needs the"
                  + " engine's own callback (composition spec §8)");
    };
  }
}
