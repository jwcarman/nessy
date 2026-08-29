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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Optional;

/**
 * What one round is still waiting on: the phase, and nothing else.
 *
 * <p>Carved out of the old {@code TurnState} when the document that holds it grew a turn id — see
 * {@link AgentState}. The three variants and every helper move here unchanged.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "phase")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Phase.Idle.class, name = "idle"),
  @JsonSubTypes.Type(value = Phase.CallingModel.class, name = "calling-model"),
  @JsonSubTypes.Type(value = Phase.WorkingTools.class, name = "working-tools")
})
public sealed interface Phase {

  /** No round in flight. The steady state. */
  record Idle() implements Phase {}

  /** A model call is in flight. */
  record CallingModel() implements Phase {}

  /** The model asked for tools. Each unsettled call has a live {@link ToolCallActor}. */
  record WorkingTools(List<ToolCallRecord> calls) implements Phase {
    public WorkingTools {
      calls = List.copyOf(calls);
    }

    @JsonIgnore
    public boolean allSettled() {
      return calls.stream().allMatch(ToolCallRecord::settled);
    }

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
