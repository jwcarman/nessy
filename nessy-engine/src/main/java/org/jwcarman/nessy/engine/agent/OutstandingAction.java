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

import java.util.Objects;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;

/**
 * One call the engine owes an outcome for, and how far along it is.
 *
 * <p>The phase exists for exactly one reason: outcomes are delivered at least once, and an approval
 * redelivered after the call has been dispatched must not dispatch it again. Presence in the
 * outstanding set is enough to make a <em>result</em> idempotent -- a call is discharged once and
 * then gone -- but approval does not remove anything, so presence alone cannot tell a first
 * approval from a second. The phase can.
 *
 * <p>It holds what the fold needs to finish a call: the call's id, its tool's name and its
 * idempotency key. The name is carried because the call effect needs it and the fold has no
 * registry to look it up in; it is the same name the model wrote, which may no longer be bound to
 * anything. What the call would do is in the {@code ActionsRequested} event, not here.
 *
 * <p>{@code since} is the seq of the event that put this call into its current phase -- {@code
 * ActionsRequested} for {@link Phase#AWAITING_APPROVAL}, {@code ToolApproved} for {@link
 * Phase#RUNNING} -- the same move as {@code AwaitingActions.requestSeq} and for the same reason: a
 * deadline has to be measured from when the phase actually started, not from whenever somebody gets
 * around to asking. It costs no migration, because this state is never stored -- it is rebuilt by
 * replay every time.
 */
public record OutstandingAction(
    CallId callId, ToolName toolName, IdempotencyKey idempotencyKey, Phase phase, Seq since) {

  public OutstandingAction {
    Objects.requireNonNull(callId, "callId must not be null");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    Objects.requireNonNull(toolName, "toolName must not be null");
    Objects.requireNonNull(phase, "phase must not be null");
    Objects.requireNonNull(since, "since must not be null");
  }

  /** Where a call is in its lifecycle. Every call starts at the first and passes through both. */
  public enum Phase {

    /** Asked about, not yet answered. Nothing has run and nothing has happened in the world. */
    AWAITING_APPROVAL,

    /** Approved and dispatched. Something may already have happened in the world. */
    RUNNING
  }

  public static OutstandingAction awaitingApproval(
      CallId callId, ToolName toolName, IdempotencyKey idempotencyKey, Seq since) {
    return new OutstandingAction(callId, toolName, idempotencyKey, Phase.AWAITING_APPROVAL, since);
  }

  public OutstandingAction running(Seq since) {
    return new OutstandingAction(callId, toolName, idempotencyKey, Phase.RUNNING, since);
  }
}
