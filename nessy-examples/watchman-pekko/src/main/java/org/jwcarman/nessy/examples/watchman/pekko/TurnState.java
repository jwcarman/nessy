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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The whole of what one watchman durably is: a transcript, and what this round is waiting on. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "state")
@JsonSubTypes({
  @JsonSubTypes.Type(value = TurnState.Idle.class, name = "idle"),
  @JsonSubTypes.Type(value = TurnState.CallingModel.class, name = "calling-model"),
  @JsonSubTypes.Type(value = TurnState.WorkingTools.class, name = "working-tools")
})
public sealed interface TurnState {

  List<Turn> transcript();

  record Idle(List<Turn> transcript) implements TurnState {
    public Idle {
      transcript = List.copyOf(transcript);
    }

    public static Idle empty() {
      return new Idle(List.of());
    }
  }

  record CallingModel(List<Turn> transcript) implements TurnState {
    public CallingModel {
      transcript = List.copyOf(transcript);
    }
  }

  record WorkingTools(List<Turn> transcript, List<ToolCallRecord> calls) implements TurnState {

    public WorkingTools {
      transcript = List.copyOf(transcript);
      calls = List.copyOf(calls);
    }

    @JsonIgnore
    public boolean allSettled() {
      return calls.stream().allMatch(ToolCallRecord::settled);
    }

    /** The re-fire rule, entire: a call with no outcome needs an actor. */
    @JsonIgnore
    public List<ToolCallRecord> unsettled() {
      return calls.stream().filter(call -> !call.settled()).toList();
    }

    public Optional<ToolCallRecord> call(String callId) {
      return calls.stream().filter(call -> call.id().equals(callId)).findFirst();
    }

    public WorkingTools replace(ToolCallRecord updated) {
      return new WorkingTools(
          transcript,
          calls.stream().map(call -> call.id().equals(updated.id()) ? updated : call).toList());
    }

    /** The transcript this round hands back to the model once every call has settled. */
    @JsonIgnore
    public List<Turn> transcriptWithResults() {
      List<Turn> lines = new ArrayList<>(transcript);
      calls.forEach(call -> lines.add(new Turn.ToolResult(call.id(), call.tool(), call.outcome())));
      return List.copyOf(lines);
    }
  }

  static List<Turn> plus(List<Turn> transcript, Turn turn) {
    List<Turn> lines = new ArrayList<>(transcript);
    lines.add(turn);
    return List.copyOf(lines);
  }
}
