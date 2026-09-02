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
 * One row of the pending-approvals projection: an approval waiting on a person, and — once someone
 * has answered — how they answered.
 *
 * <p>A row, not a truth. It is a queryable shadow kept so a page can ask "what is waiting?", which
 * is a question no door in Nessy answers: a harness observes and subscribes, and what a turn
 * persists is its phase, not the question an approver parked on.
 *
 * @param callId the tool call's id within its turn — the row's key, and what an answer settles
 * @param agentType the kind of agent the parked call belongs to
 * @param agentId the agent the parked call belongs to
 * @param tool which tool was called
 * @param action what will actually happen if this is approved, as the tool's renderer rendered it
 * @param askedAt when the projection SAW the question — not when the approver parked. Narration
 *     carries no timestamp, so this is the observer's own clock at the moment it wrote the row
 * @param expiresAt when the question stops standing, so a page can show urgency and stop offering a
 *     button that would no longer be honoured
 * @param replyToken where an answer goes. Stored deliberately: it is how a page answers a call
 *     minutes or days after the process that asked has forgotten it. Sealed with the application's
 *     own key, in the application's own table
 * @param answer {@code "approved"} or {@code "denied"}, or empty while still waiting
 * @param note a denial's reason; empty for approvals and for rows still waiting
 * @param answeredAt when the answer was seen, or empty while still waiting
 */
public record PendingApproval(
    String callId,
    String agentType,
    String agentId,
    String tool,
    String action,
    Instant askedAt,
    Instant expiresAt,
    String replyToken,
    Optional<String> answer,
    Optional<String> note,
    Optional<Instant> answeredAt) {

  /** Whether this row is still waiting on a person. */
  public boolean waiting() {
    return answer.isEmpty();
  }
}
