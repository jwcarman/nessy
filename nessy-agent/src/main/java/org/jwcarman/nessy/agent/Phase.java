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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * State that carries its own data. Pending calls exist only inside {@code AwaitingTools}; "idle
 * with outstanding calls" is unrepresentable (spec §2.2). Every phase carries enough to reconstruct
 * its outstanding effects (spec §6.1).
 *
 * <p>Carries a {@code "type"} discriminator naming the record on the wire (substrate spec §7):
 * {@code idle}, {@code awaiting-model}, {@code awaiting-tools}. The values are a compatibility
 * surface and must never change.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Phase.Idle.class, name = "idle"),
  @JsonSubTypes.Type(value = Phase.AwaitingModel.class, name = "awaiting-model"),
  @JsonSubTypes.Type(value = Phase.AwaitingTools.class, name = "awaiting-tools")
})
public sealed interface Phase {

  /**
   * Backs {@link Transition#ignore()}'s marker; public by interface rules, inert because it is an
   * ordinary Idle.
   */
  Phase SENTINEL = new Idle();

  Transition handle(AgentEvent event);

  /**
   * The effects still in flight for this phase, re-derivable on any node — the §6.1 recovery
   * invariant as a method. Every future phase must keep this total.
   */
  List<Effect> outstandingEffects();

  record Idle() implements Phase {
    @Override
    public Transition handle(AgentEvent event) {
      return switch (event) {
        case AgentEvent.Observed(var content) ->
            Transition.to(new AwaitingModel(), new Effect.CallModel())
                .commit(Message.user(content));
        case AgentEvent.ModelFinished _ -> Transition.ignore();
        case AgentEvent.ToolFinished _ -> Transition.ignore();
      };
    }

    @Override
    public List<Effect> outstandingEffects() {
      return List.of();
    }
  }

  record AwaitingModel() implements Phase {
    @Override
    public Transition handle(AgentEvent event) {
      return switch (event) {
        case AgentEvent.ModelFinished(ModelOutcome.Responded(var content, var calls, var _))
            when calls.isEmpty() ->
            Transition.to(new Idle()).commit(Message.assistant(content));
        case AgentEvent.ModelFinished(
                ModelOutcome.Responded(var content, var calls, var responseId)) ->
            Transition.to(
                    new AwaitingTools(
                        Message.assistant(content),
                        calls.stream().map(ToolCall::id).collect(Collectors.toUnmodifiableSet()),
                        List.of(),
                        responseId))
                .emit(calls.stream().map(Effect.ExecuteTool::new).map(Effect.class::cast).toList());
        case AgentEvent.ModelFinished(_) -> Transition.to(new Idle());
        case AgentEvent.ToolFinished _ -> Transition.ignore();
        case AgentEvent.Observed _ ->
            throw new IllegalStateException("observations absorb only at Idle");
      };
    }

    @Override
    public List<Effect> outstandingEffects() {
      return List.of(new Effect.CallModel());
    }
  }

  /**
   * @param pending the still-outstanding tool-call ids; normalized to a sorted, unmodifiable set so
   *     wire serialization is deterministic without codec-side sorting
   * @param responseId the id of the model response that produced {@code assistantTurn} — carried on
   *     the wire (durable-deliveries spec §2), never generated here (the reducer stays a pure fold)
   */
  record AwaitingTools(
      Message assistantTurn,
      Set<String> pending,
      List<ToolResultBlock> gathered,
      ModelResponseId responseId)
      implements Phase {

    public AwaitingTools {
      Objects.requireNonNull(assistantTurn, "assistantTurn must not be null");
      Objects.requireNonNull(responseId, "responseId must not be null");
      // A TreeSet, not Set.copyOf: pending ids serialize in a deterministic (sorted) order —
      // wire-format invariance the hand-rolled codec used to enforce by sorting on write.
      pending = Collections.unmodifiableSortedSet(new TreeSet<>(pending));
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
          yield Transition.to(new AwaitingTools(assistantTurn, left, all, responseId));
        }
        case AgentEvent.ModelFinished _ -> Transition.ignore();
        case AgentEvent.Observed _ ->
            throw new IllegalStateException("observations absorb only at Idle");
      };
    }

    @Override
    public List<Effect> outstandingEffects() {
      var byId = new HashMap<String, ToolCall>();
      for (var block : assistantTurn.content()) {
        if (block instanceof ToolUseBlock(ToolCall call, _)) {
          byId.put(call.id(), call);
        }
      }
      return pending.stream()
          .sorted()
          .map(id -> (Effect) new Effect.ExecuteTool(byId.get(id)))
          .toList();
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
