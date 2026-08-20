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
import org.jwcarman.nessy.agent.spi.ParkedCallPolicy;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.event.ToolProgress;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * The registry tool executor (§4.3): find, bind, execute, deliver. What happens when a tool parks
 * is the wiring's {@link ParkedCallPolicy}: the default (4-arg constructor) fails loudly in-band —
 * a parked turn wedges a conversation — while a durable wiring suspends the call into a slot. A
 * suspended call delivers nothing and narrates nothing.
 */
public final class RegistryToolCallExecutor implements ToolCallExecutor {

  private final ToolRegistry registry;
  private final TurnObserver turn;
  private final Executor executor;
  private final ObjectMapper mapper = new ObjectMapper();
  private final ParkedCallPolicy parkedCallPolicy;

  private static final String PARKING_UNAVAILABLE =
      "parking is unavailable in this wiring; the desk arrives with the autonomous host";

  public RegistryToolCallExecutor(
      ToolRegistry registry, AgentId id, TurnObserver turn, Executor executor) {
    this(registry, id, turn, executor, defaultPolicy(turn));
  }

  public RegistryToolCallExecutor(
      ToolRegistry registry,
      AgentId id,
      TurnObserver turn,
      Executor executor,
      ParkedCallPolicy parkedCallPolicy) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    Objects.requireNonNull(id, "id must not be null");
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
    this.parkedCallPolicy =
        Objects.requireNonNull(parkedCallPolicy, "parkedCallPolicy must not be null");
  }

  @Override
  public void executeTool(ToolCall call, Sink sink) {
    executor.execute(
        () -> {
          switch (execute(call)) {
            case ToolExecution.Immediate(ToolOutcome outcome) ->
                sink.deliver(new AgentEvent.ToolFinished(call, outcome));
            case ToolExecution.Deferred(var computation) -> {
              // suspended into its slot: nothing delivered, nothing narrated (§4.3) — the
              // completion re-enters through the slot's registered continuation
            }
          }
        });
  }

  private ToolExecution execute(ToolCall call) {
    Optional<Tool<?>> found = registry.find(call.name());
    if (found.isEmpty()) {
      return new ToolExecution.Immediate(failed(call, "unknown tool: " + call.name()));
    }
    try {
      return invoke(found.get(), call);
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return new ToolExecution.Immediate(failed(call, message));
    }
  }

  private <T> ToolExecution invoke(Tool<T> tool, ToolCall call) {
    T input = mapper.convertValue(call.arguments(), tool.inputType());
    ToolContext context = new ToolContext(call, event -> narrateProgress(call, event));
    return switch (tool.execute(input, context)) {
      case Awaited.Ready<ToolResult>(ToolResult value) -> {
        turn.on(new TurnEvent.ToolCallCompleted(call, value));
        yield new ToolExecution.Immediate(new ToolOutcome.Returned(value));
      }
      case Awaited.Deferred<ToolResult> ignored -> parkedCallPolicy.onDeferred(call);
    };
  }

  /**
   * A {@link ToolContext}'s own {@code progress} emits a {@link ToolProgress} record; that is the
   * one shape worth reading a message out of. Anything else falls back to {@code
   * String.valueOf(event)} rather than being dropped.
   */
  private void narrateProgress(ToolCall call, Object event) {
    String message =
        event instanceof ToolProgress(var _, String progressMessage)
            ? progressMessage
            : String.valueOf(event);
    turn.on(new TurnEvent.ToolCallProgressed(call, message));
  }

  private ToolOutcome failed(ToolCall call, String message) {
    ToolResult error = ToolResult.error(message);
    turn.on(new TurnEvent.ToolCallCompleted(call, error));
    return new ToolOutcome.Failed(new ToolError(message));
  }

  /** The 4-arg constructor's default: fails loudly in-band rather than suspending silently. */
  private static ParkedCallPolicy defaultPolicy(TurnObserver turn) {
    return call -> {
      ToolResult error = ToolResult.error(PARKING_UNAVAILABLE);
      turn.on(new TurnEvent.ToolCallCompleted(call, error));
      return new ToolExecution.Immediate(
          new ToolOutcome.Failed(new ToolError(PARKING_UNAVAILABLE)));
    };
  }
}
