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

import java.time.Clock;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentSubscriber;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyToken;

/**
 * Keeps {@link PendingApprovalsRepository} up to date by listening to one agent.
 *
 * <p><b>Why a projection at all.</b> Reading the write model would be better — no second write,
 * nothing to drift — and it is not available: an agent persists its backlog and which turn is
 * running, a turn persists its phase, and the actor waiting on a person is ephemeral. "What is
 * waiting" is not a function of anything on disk, so a page that wants to ask must be told.
 *
 * <p><b>Where the address comes from.</b> Narration says a question was asked; it does not say
 * where to answer, because a reply address is authority rather than description and does not travel
 * in an event every subscriber sees. The approver that parked the call knows it, and hands it here
 * through {@link #expecting}. A row is written once both halves have arrived, which is why this
 * listener holds the address rather than the repository.
 *
 * <p><b>It survives a restart</b> without a durable copy of its own: a recovered turn re-runs the
 * calls it never settled, which asks the approver again and narrates the question again, so the
 * rows are rewritten as the agents recover. The insert is idempotent by call id for exactly that
 * reason.
 *
 * <p><b>What it does not do.</b> {@code Harness#subscribe} listens to ONE agent, so one of these
 * covers one agent id. An application running many agents registers one per agent; there is no
 * subscribe-to-everything door, and inventing one here would mean guessing at ids.
 */
public class PendingApprovalsListener implements AgentSubscriber {

  private final PendingApprovalsRepository repository;
  private final AgentType agentType;
  private final AgentId agentId;
  private final Clock clock;
  private final java.util.Map<CallId, ReplyToken> addresses =
      new java.util.concurrent.ConcurrentHashMap<>();

  public PendingApprovalsListener(
      PendingApprovalsRepository repository, AgentType agentType, AgentId agentId, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Told by an approver, at the moment it decided a person was needed, where that person's answer
   * should go.
   */
  public void expecting(CallId callId, ReplyToken replyTo) {
    addresses.put(Objects.requireNonNull(callId, "callId must not be null"), replyTo);
  }

  @Override
  public void on(AgentEvent event) {
    switch (event) {
      case AgentEvent.ApprovalRequested asked -> write(asked);
      case AgentEvent.ApprovalDecided decided -> settle(decided);
      default -> {
        // Every other event belongs to somebody else.
      }
    }
  }

  private void write(AgentEvent.ApprovalRequested asked) {
    ReplyToken replyTo = addresses.get(asked.callId());
    if (replyTo == null) {
      // Nobody told us where to answer, so a row would show a button that cannot work.
      return;
    }
    repository.asked(
        new PendingApproval(
            asked.callId(),
            agentType,
            agentId,
            asked.toolName(),
            asked.description(),
            clock.instant(),
            asked.expiresAt(),
            replyTo.value(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty()));
  }

  private void settle(AgentEvent.ApprovalDecided decided) {
    String answer = decided.result() instanceof ApprovalResult.Approved ? "approved" : "denied";
    String note = decided.result() instanceof ApprovalResult.Denied denied ? denied.reason() : null;
    repository.answered(agentType, agentId, decided.callId(), answer, note, clock.instant());
    addresses.remove(decided.callId());
  }
}
