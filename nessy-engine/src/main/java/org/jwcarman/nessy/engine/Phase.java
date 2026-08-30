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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What a turn is waiting on, and nothing else.
 *
 * <p>Lives on the TURN's document rather than the agent's. That is the whole reason the agent's
 * state stays flat: a turn advancing through a model call and eight tool calls rewrites this, not
 * the backlog.
 *
 * <p>Wire names are a compatibility surface — a turn parked overnight is read back by name.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "phase")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Phase.Starting.class, name = "starting"),
  @JsonSubTypes.Type(value = Phase.CallingModel.class, name = "calling-model"),
  @JsonSubTypes.Type(value = Phase.WorkingTools.class, name = "working-tools")
})
public sealed interface Phase {

  /** The turn exists and has claimed its input; nothing has been asked of the model yet. */
  record Starting() implements Phase {}

  /** A model call is in flight. */
  record CallingModel() implements Phase {}

  /**
   * The model asked for tools, and these are the calls not yet settled.
   *
   * <p>Ids only. What each call RETURNED is content, and content the size of whatever a tool
   * decided to hand back — keeping it here would make a turn's document grow with what its tools
   * do, which is the thing claims exist to prevent.
   */
  record WorkingTools(java.util.List<String> callIds) implements Phase {
    public WorkingTools {
      callIds = java.util.List.copyOf(callIds);
    }
  }
}
