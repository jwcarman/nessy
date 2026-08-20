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
package org.jwcarman.nessy.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.ToolError;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * The non-parking tool executor (§4.3): find, bind, execute, deliver — and a park attempt fails
 * loudly in-band, because a parked turn wedges a conversation. The desk arrives with the autonomous
 * wiring (Plan 4). The {@link ConversationId} bridge is interim vocabulary (plan decision 3).
 */
public final class RegistryToolCallExecutor implements ToolCallExecutor {

  private final ToolRegistry registry;
  private final ConversationId bridgedId;
  private final TurnObserver turn;
  private final Executor executor;
  private final ObjectMapper mapper = new ObjectMapper();

  public RegistryToolCallExecutor(
      ToolRegistry registry, AgentId id, TurnObserver turn, Executor executor) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.bridgedId = new ConversationId(Objects.requireNonNull(id, "id must not be null").value());
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
  }

  @Override
  public void executeTool(ToolCall call, Sink sink) {
    executor.execute(() -> sink.deliver(new AgentEvent.ToolFinished(call, execute(call))));
  }

  private ToolOutcome execute(ToolCall call) {
    Optional<Tool<?>> found = registry.find(call.name());
    if (found.isEmpty()) {
      return failed(call, "unknown tool: " + call.name());
    }
    try {
      ToolResult result = invoke(found.get(), call);
      turn.on(new TurnEvent.ToolCallCompleted(call, result));
      return new ToolOutcome.Returned(result);
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return failed(call, message);
    }
  }

  private <T> ToolResult invoke(Tool<T> tool, ToolCall call) {
    T input = mapper.convertValue(call.arguments(), tool.inputType());
    ToolContext context =
        new ToolContext(
            bridgedId,
            call,
            event -> turn.on(new TurnEvent.ToolCallProgressed(call, String.valueOf(event))));
    return switch (tool.execute(input, context)) {
      case Awaited.Ready<ToolResult>(ToolResult value) -> value;
      case Awaited.Parked<ToolResult> ignored ->
          throw new IllegalStateException(
              "parking is unavailable in this wiring; the desk arrives with the autonomous host");
    };
  }

  private ToolOutcome failed(ToolCall call, String message) {
    ToolResult error = ToolResult.error(message);
    turn.on(new TurnEvent.ToolCallCompleted(call, error));
    return new ToolOutcome.Failed(new ToolError(message));
  }
}
