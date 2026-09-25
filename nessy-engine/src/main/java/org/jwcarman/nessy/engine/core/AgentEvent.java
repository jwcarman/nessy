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
package org.jwcarman.nessy.engine.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.Seq;
import org.jwcarman.nessy.inference.TurnId;
import org.jwcarman.nessy.inference.tool.CallId;
import org.jwcarman.nessy.inference.tool.ToolName;

/**
 * What happened. Facts, in order, and the only thing that moves an {@link AgentState}.
 *
 * <p><b>No payloads.</b> Every one of these carries identifiers, status, a human decision or a
 * count -- and a {@link PayloadRef} where content would otherwise be. That is what keeps the stream
 * small enough to replay on every command, and what keeps every type in it one of Nessy's own.
 *
 * <p>Two scopes live here. Most events belong to a turn and carry its id; {@link Terminated}
 * belongs to the agent's life and sits between turns.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = AgentEvent.TurnStarted.class, name = "turn-started"),
  @JsonSubTypes.Type(value = AgentEvent.InferenceAnswered.class, name = "inference-answered"),
  @JsonSubTypes.Type(value = AgentEvent.InferenceRefused.class, name = "inference-refused"),
  @JsonSubTypes.Type(value = AgentEvent.InferenceFailed.class, name = "inference-failed"),
  @JsonSubTypes.Type(value = AgentEvent.ActionsRequested.class, name = "actions-requested"),
  @JsonSubTypes.Type(value = AgentEvent.ToolApproved.class, name = "tool-approved"),
  @JsonSubTypes.Type(value = AgentEvent.ToolDenied.class, name = "tool-denied"),
  @JsonSubTypes.Type(value = AgentEvent.ToolSucceeded.class, name = "tool-succeeded"),
  @JsonSubTypes.Type(value = AgentEvent.ToolFailed.class, name = "tool-failed"),
  @JsonSubTypes.Type(value = AgentEvent.Terminated.class, name = "terminated")
})
public sealed interface AgentEvent {

  /** Where this event sits. Strictly increasing, and what {@code apply} checks. */
  Seq seq();

  /** A turn opened on an observation. Its {@link TurnId} is this event's own position. */
  record TurnStarted(Seq seq, TurnId turn, PayloadRef observation) implements AgentEvent {}

  /** The model answered, and the turn is over. */
  record InferenceAnswered(Seq seq, TurnId turn, PayloadRef answer) implements AgentEvent {}

  /** The model declined, and would decline again. */
  record InferenceRefused(Seq seq, TurnId turn, String category) implements AgentEvent {}

  /**
   * The model was not reached, or did not answer.
   *
   * <p>Carries the {@link Failure} rather than a sentence: a failed inference is the engine's
   * problem and what matters is whether trying again could work.
   */
  record InferenceFailed(Seq seq, TurnId turn, Failure failure) implements AgentEvent {}

  /**
   * The model asked for work before it would answer.
   *
   * <p>The calls are in the spine because the state must know what it is waiting for; their
   * arguments are behind {@code request}, because only the tool ever reads those.
   */
  record ActionsRequested(Seq seq, TurnId turn, PayloadRef request, List<Requested> calls)
      implements AgentEvent {
    public ActionsRequested {
      calls = List.copyOf(calls);
    }
  }

  /** One call the model asked for: which call, and which tool. */
  record Requested(CallId callId, ToolName toolName) {}

  /** A call was allowed to run. */
  record ToolApproved(Seq seq, TurnId turn, CallId callId, Optional<String> reference)
      implements AgentEvent {}

  /** A call was refused and never ran. */
  record ToolDenied(Seq seq, TurnId turn, CallId callId, String reason, Optional<String> reference)
      implements AgentEvent {}

  /** A call ran and produced something. */
  record ToolSucceeded(Seq seq, TurnId turn, CallId callId, PayloadRef result)
      implements AgentEvent {}

  /**
   * A call ran and did not produce content.
   *
   * <p>Carries a sentence rather than a {@link Failure}, which is the opposite of {@link
   * InferenceFailed} and deliberately so: a failed tool call is the <em>model's</em> problem, it is
   * going to read this, and what matters is that it can tell what to do next.
   *
   * <p>This is one of only two free-text fields in the whole stream. It names what went wrong and
   * never the values involved -- the stream is stored in the clear and kept for a long time.
   */
  record ToolFailed(Seq seq, TurnId turn, CallId callId, String message) implements AgentEvent {}

  /**
   * The agent will accept nothing further.
   *
   * <p>Agent-scoped, so it carries no {@link TurnId}: it sits between turns rather than inside one.
   * Applying it yields {@link AgentState.Terminal}, which refuses everything -- so termination is
   * irreversible by construction rather than by a flag somebody must remember to check.
   */
  record Terminated(Seq seq) implements AgentEvent {}
}
