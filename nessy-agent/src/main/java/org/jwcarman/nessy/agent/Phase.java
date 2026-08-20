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
package org.jwcarman.nessy.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * State that carries its own data. Pending calls exist only inside {@code AwaitingTools}; "idle
 * with outstanding calls" is unrepresentable (spec §2.2). Every phase carries enough to reconstruct
 * its outstanding effects (spec §6.1).
 */
public sealed interface Phase {

  /**
   * Backs {@link Transition#ignore()}'s marker; public by interface rules, inert because it is an
   * ordinary Idle.
   */
  Phase SENTINEL = new Idle();

  Transition handle(AgentEvent event);

  record Idle() implements Phase {
    @Override
    public Transition handle(AgentEvent event) {
      return switch (event) {
        case AgentEvent.Observed(var content) ->
            Transition.to(new AwaitingModel(), new Effect.CallModel())
                .commit(Message.user(content));
        case AgentEvent.ModelFinished ignored -> Transition.ignore();
        case AgentEvent.ToolFinished ignored -> Transition.ignore();
      };
    }
  }

  record AwaitingModel() implements Phase {
    @Override
    public Transition handle(AgentEvent event) {
      return switch (event) {
        case AgentEvent.ModelFinished(ModelOutcome.Responded(var content, var calls))
            when calls.isEmpty() ->
            Transition.to(new Idle()).commit(Message.assistant(content));
        case AgentEvent.ModelFinished(ModelOutcome.Responded(var content, var calls)) ->
            Transition.to(
                    new AwaitingTools(
                        Message.assistant(content),
                        calls.stream().map(ToolCall::id).collect(Collectors.toUnmodifiableSet()),
                        List.of()))
                .emit(calls.stream().map(Effect.ExecuteTool::new).map(Effect.class::cast).toList());
        case AgentEvent.ModelFinished(ModelOutcome.Failed ignored) -> Transition.to(new Idle());
        case AgentEvent.ToolFinished ignored -> Transition.ignore();
        case AgentEvent.Observed ignored ->
            throw new IllegalStateException("observations absorb only at Idle");
      };
    }
  }

  record AwaitingTools(Message assistantTurn, Set<String> pending, List<ToolResultBlock> gathered)
      implements Phase {

    public AwaitingTools {
      Objects.requireNonNull(assistantTurn, "assistantTurn must not be null");
      pending = Set.copyOf(pending);
      gathered = List.copyOf(gathered);
      if (pending.isEmpty()) {
        throw new IllegalArgumentException("awaiting tools with nothing pending is not a phase");
      }
      Set<String> toolUseIds =
          assistantTurn.content().stream()
              .filter(ToolUseBlock.class::isInstance)
              .map(ToolUseBlock.class::cast)
              .map(b -> b.call().id())
              .collect(Collectors.toUnmodifiableSet());
      Set<String> missing = new HashSet<>(pending);
      missing.removeAll(toolUseIds);
      if (!missing.isEmpty()) {
        throw new IllegalArgumentException(
            "pending ids missing from the assistant turn's tool-use blocks: " + missing);
      }
    }

    @Override
    public Transition handle(AgentEvent event) {
      return switch (event) {
        case AgentEvent.ToolFinished(var call, var outcome) -> {
          if (!pending.contains(call.id())) {
            yield Transition.ignore(); // duplicate or stale — ToolCallId dedup (spec §2.5)
          }
          var left = new HashSet<>(pending);
          left.remove(call.id());
          var all = new ArrayList<>(gathered);
          all.add(resultBlock(call, outcome));
          if (left.isEmpty()) {
            yield Transition.to(new AwaitingModel(), new Effect.CallModel())
                .commit(assistantTurn, Message.toolResults(List.copyOf(all)));
          }
          yield Transition.to(new AwaitingTools(assistantTurn, left, all));
        }
        case AgentEvent.ModelFinished ignored -> Transition.ignore();
        case AgentEvent.Observed ignored ->
            throw new IllegalStateException("observations absorb only at Idle");
      };
    }

    private static ToolResultBlock resultBlock(ToolCall call, ToolOutcome outcome) {
      return switch (outcome) {
        case ToolOutcome.Returned(var result) ->
            new ToolResultBlock(call.id(), result.content(), result.isError());
        case ToolOutcome.Failed(var error) -> new ToolResultBlock(call.id(), error.message(), true);
      };
    }
  }
}
