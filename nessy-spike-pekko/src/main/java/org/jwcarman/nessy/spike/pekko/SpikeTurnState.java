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
import java.util.Objects;
import java.util.Optional;

/**
 * THROWAWAY SPIKE. The whole of what one agent durably is: a transcript, plus what the turn is
 * waiting on.
 *
 * <p>Note what this does NOT contain and does not need to: no lease holder, no lease expiry, no
 * dispatch records, no outbox, no "is a driver alive" flag. In the Pekko shape the entity is
 * single-threaded and singleton across the cluster, so "who owns this turn" is not a question the
 * state has to answer.
 *
 * <p>Note also that the transcript lives HERE rather than in a separate memory store. That makes
 * one durable write cover both the control state and the conversation — Pekko gives no way to make
 * two stores atomic, so the spike does not ask it to.
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

  /** A model call is in flight. If we crash here, nothing but this state knows to re-issue it. */
  record CallingModel(List<String> transcript) implements SpikeTurnState {
    public CallingModel {
      transcript = List.copyOf(transcript);
    }
  }

  /** The model asked for tools. Some may be running; some may be parked on a human. */
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
      return calls.stream().map(SpikeToolCall::phase).allMatch(SpikeCallPhase::settled);
    }

    public Optional<SpikeToolCall> call(String callId) {
      return calls.stream().filter(call -> call.id().equals(callId)).findFirst();
    }

    /** This state with one call moved on; every other call untouched. */
    public WorkingTools with(String callId, SpikeCallPhase phase) {
      Objects.requireNonNull(phase, "phase must not be null");
      return new WorkingTools(
          transcript,
          calls.stream().map(call -> call.id().equals(callId) ? call.in(phase) : call).toList());
    }

    /** The transcript this turn hands back to the model once every call has settled. */
    @JsonIgnore
    public List<String> transcriptWithResults() {
      List<String> lines = new ArrayList<>(transcript);
      calls.forEach(call -> lines.add("tool: " + call.outcome()));
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
