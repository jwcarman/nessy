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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * State that carries its own data. Outstanding calls exist only inside {@code AwaitingTools}; "idle
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
        case ToolCallEvent _ -> Transition.ignore();
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
            Transition.to(AwaitingTools.opening(Message.assistant(content), calls, responseId))
                .emit(
                    calls.stream().map(Effect.SeekApproval::new).map(Effect.class::cast).toList());
        case AgentEvent.ModelFinished(_) -> Transition.to(new Idle());
        case ToolCallEvent _ -> Transition.ignore();
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
   * @param calls each call the assistant turn asked for, keyed by call id, with where its lifecycle
   *     stands — a sorted map so the wire form is deterministic
   * @param responseId the id of the model response that produced {@code assistantTurn} — carried on
   *     the wire (durable-deliveries spec §2), never generated here (the reducer stays a pure fold)
   */
  record AwaitingTools(
      Message assistantTurn, Map<String, CallStatus> calls, ModelResponseId responseId)
      implements Phase {

    public AwaitingTools {
      Objects.requireNonNull(assistantTurn, "assistantTurn must not be null");
      Objects.requireNonNull(responseId, "responseId must not be null");
      calls = Collections.unmodifiableSortedMap(new TreeMap<>(calls));
      if (calls.isEmpty()) {
        throw new IllegalArgumentException("awaiting tools with no calls is not a phase");
      }
      Set<String> toolUseIds = toolUseIds(assistantTurn);
      Set<String> unknown = new HashSet<>(calls.keySet());
      unknown.removeAll(toolUseIds);
      if (!unknown.isEmpty()) {
        throw new IllegalArgumentException(
            "call ids missing from the assistant turn's tool-use blocks: " + unknown);
      }
    }

    /** The opening shape: every requested call {@link CallStatus.Pending}. */
    static AwaitingTools opening(
        Message assistantTurn, List<ToolCall> requested, ModelResponseId responseId) {
      Map<String, CallStatus> pending = new TreeMap<>();
      for (ToolCall call : requested) {
        pending.put(call.id(), new CallStatus.Pending());
      }
      return new AwaitingTools(assistantTurn, pending, responseId);
    }

    @Override
    public Transition handle(AgentEvent event) {
      return switch (event) {
        case AgentEvent.Observed _ ->
            throw new IllegalStateException("observations absorb only at Idle");
        case AgentEvent.ModelFinished _ -> Transition.ignore();
        case ToolCallEvent e -> route(e);
      };
    }

    /**
     * The phase's whole part in a call's life (deferral-by-callback spec §6.1): find whose fact
     * this is, let that call decide, put the answer back — naming no state, no call event and no
     * id.
     */
    private Transition route(ToolCallEvent event) {
      CallStatus current = calls.get(event.toolCallId());
      if (current == null) {
        return Transition.ignore(); // a call this turn never asked for
      }
      return switch (current.handle(event)) {
        case ToolCallTransition.Dropped _ -> Transition.ignore();
        case ToolCallTransition.Advanced(var next, var effects) ->
            advance(event.toolCallId(), next, effects);
      };
    }

    /**
     * The turn-level decision, which no individual call can make: once every call has a result,
     * commit the assistant turn and the results in this phase's own insertion order and go back to
     * the model.
     */
    private Transition advance(String callId, CallStatus next, List<Effect> effects) {
      AwaitingTools updated = with(callId, next);
      if (updated.calls.values().stream().allMatch(status -> status.resultBlock().isPresent())) {
        return Transition.to(new AwaitingModel(), new Effect.CallModel())
            .commit(assistantTurn, Message.toolResults(updated.resultsInTurnOrder()));
      }
      return Transition.to(updated).emit(effects);
    }

    private AwaitingTools with(String callId, CallStatus status) {
      Map<String, CallStatus> updated = new TreeMap<>(calls);
      updated.put(callId, status);
      return new AwaitingTools(assistantTurn, updated, responseId);
    }

    /** Results in the order the assistant turn asked, which is the order the model expects. */
    private List<ContentBlock> resultsInTurnOrder() {
      List<ContentBlock> results = new ArrayList<>();
      for (ToolCall call : requestedCalls()) {
        CallStatus status = calls.get(call.id());
        if (status != null) {
          status.resultBlock().ifPresent(results::add);
        }
      }
      return List.copyOf(results);
    }

    @Override
    public List<Effect> outstandingEffects() {
      List<Effect> effects = new ArrayList<>();
      for (ToolCall call : requestedCalls()) {
        CallStatus status = calls.get(call.id());
        // Not a default arm of a switch: a Map.get miss is a programming error, surfaced loudly.
        if (status == null) {
          throw new IllegalStateException("no status for call " + call.id());
        }
        // The re-fire rule is the state's own, stated once (CallStatus#outstanding).
        effects.addAll(status.outstanding(call));
      }
      return List.copyOf(effects);
    }

    /** The calls the assistant turn asked for, in its own order. */
    List<ToolCall> requestedCalls() {
      List<ToolCall> requested = new ArrayList<>();
      for (var block : assistantTurn.content()) {
        if (block instanceof ToolUseBlock(ToolCall call, _)) {
          requested.add(call);
        }
      }
      return requested;
    }

    private static Set<String> toolUseIds(Message assistantTurn) {
      return assistantTurn.content().stream()
          .filter(ToolUseBlock.class::isInstance)
          .map(ToolUseBlock.class::cast)
          .map(b -> b.call().id())
          .collect(Collectors.toUnmodifiableSet());
    }
  }
}
