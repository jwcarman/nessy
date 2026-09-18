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
 * <p>The tool's name is carried because the call effect needs it and the fold has no registry to
 * look it up in. It is the same name the model wrote, which may no longer be bound to anything.
 */
public record Outstanding(ToolName toolName, Phase phase) {

  public Outstanding {
    Objects.requireNonNull(toolName, "toolName must not be null");
    Objects.requireNonNull(phase, "phase must not be null");
  }

  /** Where a call is in its lifecycle. Every call starts at the first and passes through both. */
  public enum Phase {

    /** Asked about, not yet answered. Nothing has run and nothing has happened in the world. */
    AWAITING_APPROVAL,

    /** Approved and dispatched. Something may already have happened in the world. */
    RUNNING
  }

  public static Outstanding awaitingApproval(ToolName toolName) {
    return new Outstanding(toolName, Phase.AWAITING_APPROVAL);
  }

  public Outstanding running() {
    return new Outstanding(toolName, Phase.RUNNING);
  }
}
