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
package org.jwcarman.nessy.examples.watchman.pekko;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Optional;

/**
 * What one watchman durably is: the phase, and what this round is still waiting on. <b>Nothing
 * else.</b>
 *
 * <p>The transcript used to live here, and the soak measured what that costs: a {@code
 * DurableStateBehavior} rewrites its whole document on every revision, so an embedded transcript
 * means every fold rewrites the entire conversation. Measured on the running watchman — 1,709 bytes
 * at revision 5, 24,151 at revision 64. It now lives in {@link Transcript}, appended one row per
 * turn, and this record is flat forever: a handful of in-flight calls at most, cleared the moment
 * the round finishes.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "state")
@JsonSubTypes({
  @JsonSubTypes.Type(value = TurnState.Idle.class, name = "idle"),
  @JsonSubTypes.Type(value = TurnState.CallingModel.class, name = "calling-model"),
  @JsonSubTypes.Type(value = TurnState.WorkingTools.class, name = "working-tools")
})
public sealed interface TurnState {

  /** No round in flight. The steady state, and the smallest document this agent ever writes. */
  record Idle() implements TurnState {}

  /** A model call is in flight. */
  record CallingModel() implements TurnState {}

  /** The model asked for tools. Each unsettled call has a live {@link ToolCallActor}. */
  record WorkingTools(List<ToolCallRecord> calls) implements TurnState {

    public WorkingTools {
      calls = List.copyOf(calls);
    }

    @JsonIgnore
    public boolean allSettled() {
      return calls.stream().allMatch(ToolCallRecord::settled);
    }

    /** The re-fire rule, entire: a call that has not settled needs an actor. */
    @JsonIgnore
    public List<ToolCallRecord> unsettled() {
      return calls.stream().filter(call -> !call.settled()).toList();
    }

    public Optional<ToolCallRecord> call(String callId) {
      return calls.stream().filter(call -> call.id().equals(callId)).findFirst();
    }

    public WorkingTools replace(ToolCallRecord updated) {
      return new WorkingTools(
          calls.stream().map(call -> call.id().equals(updated.id()) ? updated : call).toList());
    }
  }
}
