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
package org.jwcarman.nessy.spike.pekko;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * THROWAWAY SPIKE. The whole of what one agent durably is.
 *
 * <p>What is NOT here is the point of round 3. No lease holder, no lease expiry, no dispatch
 * records, no outbox — and now, no per-call state machine either. The agent persists what it asked
 * for and what came back. Every question of "is this call awaiting approval, running, retrying, or
 * timed out?" belongs to that call's own actor, which is the only thing that can answer it.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "state")
@JsonSubTypes({
  @JsonSubTypes.Type(value = SpikeTurnState.Idle.class, name = "idle"),
  @JsonSubTypes.Type(value = SpikeTurnState.CallingModel.class, name = "calling-model"),
  @JsonSubTypes.Type(value = SpikeTurnState.WorkingTools.class, name = "working-tools")
})
public sealed interface SpikeTurnState extends SpikeSerializable {

  List<String> transcript();

  /** No turn in flight. */
  record Idle(List<String> transcript) implements SpikeTurnState {
    public Idle {
      transcript = List.copyOf(transcript);
    }

    public static Idle empty() {
      return new Idle(List.of());
    }
  }

  /** A model call is in flight. */
  record CallingModel(List<String> transcript) implements SpikeTurnState {
    public CallingModel {
      transcript = List.copyOf(transcript);
    }
  }

  /** The model asked for tools. Each has a live {@link ToolCallActor} until it settles. */
  record WorkingTools(List<String> transcript, List<SpikeToolCall> calls)
      implements SpikeTurnState {

    public WorkingTools {
      transcript = List.copyOf(transcript);
      calls = List.copyOf(calls);
      if (calls.isEmpty()) {
        throw new IllegalArgumentException("working no tools is not a state");
      }
    }

    @JsonIgnore
    public boolean allSettled() {
      return calls.stream().allMatch(SpikeToolCall::settled);
    }

    /**
     * The whole of recovery's re-fire rule (compare round 2's {@code outstanding()} plus a
     * per-state {@code List<Effect>} contract): a call with no outcome needs an actor.
     */
    @JsonIgnore
    public List<SpikeToolCall> unsettled() {
      return calls.stream().filter(call -> !call.settled()).toList();
    }

    public Optional<SpikeToolCall> call(String callId) {
      return calls.stream().filter(call -> call.id().equals(callId)).findFirst();
    }

    public WorkingTools settle(String callId, String outcome) {
      return new WorkingTools(
          transcript,
          calls.stream()
              .map(call -> call.id().equals(callId) ? call.settledWith(outcome) : call)
              .toList());
    }

    /** The transcript this turn hands back to the model once every call has settled. */
    @JsonIgnore
    public List<String> transcriptWithResults() {
      List<String> lines = new ArrayList<>(transcript);
      calls.forEach(call -> lines.add("tool: " + call.tool() + " -> " + call.outcome()));
      return List.copyOf(lines);
    }
  }

  /** The transcript with one more line — the only way any state grows. */
  static List<String> plus(List<String> transcript, String line) {
    List<String> lines = new ArrayList<>(transcript);
    lines.add(line);
    return List.copyOf(lines);
  }
}
