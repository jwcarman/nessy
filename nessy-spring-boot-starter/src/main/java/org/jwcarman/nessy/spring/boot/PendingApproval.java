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
package org.jwcarman.nessy.spring.boot;

import java.time.Instant;
import java.util.Optional;

/**
 * One row of the pending-approvals projection (watchman spec §1.3): an approval that parked, and —
 * once the desk has answered and the fold has published the fact — how it was answered.
 *
 * <p>A row, not a truth. The phase is the ledger; this is a queryable shadow of it, kept so that a
 * page can ask "what is waiting?", which is a question no door in Nessy answers: the desk answers
 * by id or coordinates, the Continuum client has no read door, and the phase is per agent.
 *
 * @param computationId the parked approval's computation id — the row's key, and what {@code
 *     ApprovalDesk#approve(ComputationId, String, String)} takes
 * @param agentType the recipe the parked call belongs to
 * @param agentId the scope the parked call belongs to
 * @param callId the tool call's id within its turn — the second half of the desk's coordinate door
 * @param action the {@code ActionContributor}'s rendered line: what will actually happen if this is
 *     approved, frozen at enrichment
 * @param requestJson the whole frozen {@link org.jwcarman.nessy.api.tool.approval.ApprovalRequest}
 *     as JSON — the evidence the decision was made on
 * @param parkedAt when the park's fact was applied; empty only in the out-of-order case where the
 *     answer's fact arrived before the park's
 * @param answer {@code "approved"} or {@code "denied"}, or empty while it is still waiting
 * @param reference the answer's opaque pointer into whatever system produced it, if it carried one
 * @param note a denial's reason; empty for approvals and for rows still waiting
 * @param answeredAt when the answer's fact was applied, or empty while it is still waiting
 */
public record PendingApproval(
    String computationId,
    Optional<String> agentType,
    Optional<String> agentId,
    Optional<String> callId,
    Optional<String> action,
    Optional<String> requestJson,
    Optional<Instant> parkedAt,
    Optional<String> answer,
    Optional<String> reference,
    Optional<String> note,
    Optional<Instant> answeredAt) {

  /** Still waiting: nobody has answered, and the park's own facts did arrive. */
  public boolean isPending() {
    return answer.isEmpty() && requestJson.isPresent();
  }
}
