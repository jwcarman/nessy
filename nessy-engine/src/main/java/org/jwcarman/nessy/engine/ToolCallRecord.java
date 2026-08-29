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

import java.time.Instant;

/**
 * One tool call, as the AGENT persists it — and no more than that.
 *
 * <p>{@code settled} is a flag rather than the result text, deliberately: the RESULT is a
 * transcript turn, appended once and never rewritten, so keeping a copy of it here would put a
 * tool's whole output ({@code df} on a big box, a Docker inventory) into a document that is
 * rewritten on every revision. The agent only ever asks "is this call done?", so a boolean is the
 * whole of what it needs.
 *
 * <p>Two fields exist that a pure actor design would not need, and both are earned:
 *
 * <ul>
 *   <li>{@code decision} — a human's answer must be durable BEFORE the page is told it landed;
 *   <li>{@code askedAt} — the approvals page shows dwell time and {@link ApprovalActor} recomputes
 *       its deadline from it after a restart.
 * </ul>
 *
 * <p>What is still absent is the state machine: there is no {@code AwaitingApproval | Running |
 * Denied} enumeration, no admission matrix and no per-variant re-fire rule. Those live in the
 * behaviour of {@link ToolCallActor}.
 */
public record ToolCallRecord(
    String id,
    String tool,
    String argumentsClaimId,
    String action,
    Instant askedAt,
    Decision decision,
    boolean settled) {

  /** A human's answer, and who gave it. Persisted, because it cannot be reconstructed. */
  public record Decision(boolean approved, String by, String note, Instant at) {}

  public static ToolCallRecord asked(
      String id, String tool, String argumentsClaimId, String action, Instant now) {
    return new ToolCallRecord(id, tool, argumentsClaimId, action, now, null, false);
  }

  public boolean decided() {
    return decision != null;
  }

  public ToolCallRecord decidedBy(Decision made) {
    return new ToolCallRecord(id, tool, argumentsClaimId, action, askedAt, made, settled);
  }

  public ToolCallRecord settle() {
    return new ToolCallRecord(id, tool, argumentsClaimId, action, askedAt, decision, true);
  }
}
