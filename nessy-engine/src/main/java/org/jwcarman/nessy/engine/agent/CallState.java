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

/**
 * What one tool call is waiting on.
 *
 * <p>These arms exist to answer exactly one question: what should happen if this process dies right
 * now? That is why {@link Parked} carries no deadline — the deadline is a reminder row, and a
 * second copy here could only drift from it.
 *
 * <p>The distinction between {@link Running} and {@link Parked} is the whole reason there are four
 * arms rather than two. A running call is re-run on recovery because nobody else will answer it; a
 * parked one is left alone, because someone is holding a reply token and re-asking would mint a
 * second one and invalidate the first.
 *
 * <p>Wire names are a compatibility surface: a turn parked overnight is read back by name.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "call")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CallState.Approving.class, name = "approving"),
  @JsonSubTypes.Type(value = CallState.Running.class, name = "running"),
  @JsonSubTypes.Type(value = CallState.Parked.class, name = "parked"),
  @JsonSubTypes.Type(value = CallState.Completed.class, name = "completed")
})
public sealed interface CallState {

  /**
   * The approver was asked and has not answered. Asking again is safe.
   *
   * <p>The tool name rides along because recovery has to re-ask, and the asking message it would
   * otherwise read the name from is a claim that may already be gone.
   */
  record Approving(String toolName) implements CallState {}

  /** Approved, and the tool is running. Running again is safe; tool execution is at-least-once. */
  record Running(String toolName) implements CallState {}

  /** Waiting on the world: someone holds a reply token and an alarm is armed. */
  record Parked() implements CallState {}

  /** Its result is in claims. Nothing to redo. */
  record Completed() implements CallState {}
}
