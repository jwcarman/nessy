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
package org.jwcarman.nessy.engine.agent;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the agent is doing, as data rather than as an actor's position in a behavior tree.
 *
 * <p>{@link Idle} is an ARM, not the absence of a turn, so going to sleep is a transition that can
 * be tested rather than a stale-snapshot check bolted onto a nudge.
 *
 * <p>Wire names are a compatibility surface: a turn parked overnight is read back by name.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "phase")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Phase.Idle.class, name = "idle"),
  @JsonSubTypes.Type(value = Phase.CallingModel.class, name = "calling-model"),
  @JsonSubTypes.Type(value = Phase.WorkingTools.class, name = "working-tools")
})
public sealed interface Phase {

  /** No turn is running. The agent may take from the backlog. */
  record Idle() implements Phase {}

  /** A model call is in flight. */
  record CallingModel() implements Phase {}

  /**
   * The model asked for tools, and this is what each call is waiting on.
   *
   * <p>Ids and small statuses only. What a call RETURNED is content, and content the size of
   * whatever a tool decided to hand back — keeping it here would make the document grow with what
   * its tools do, which is the thing claims exist to prevent.
   */
  record WorkingTools(Map<String, CallState> calls) implements Phase {

    public WorkingTools {
      calls = Map.copyOf(calls);
    }

    /** The same phase with one call in a new state. */
    public WorkingTools with(String callId, CallState state) {
      Map<String, CallState> next = new LinkedHashMap<>(calls);
      next.put(callId, state);
      return new WorkingTools(next);
    }

    /** Whether every call has its result in claims, so the exchange can go back to the model. */
    public boolean allSettled() {
      return calls.values().stream().allMatch(CallState.Completed.class::isInstance);
    }
  }
}
