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
package org.jwcarman.nessy.examples.watchman;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;

/**
 * The two writers of the board.
 *
 * <p>As the {@link Approver}, it writes a question down the moment the engine asks -- the request
 * carries everything the row needs, the reply token included, so there is no second half to wait
 * for. As the {@link AgentEventListener}, it hears the engine settle a call and marks the row
 * answered, which is how a decision made from another tab, or by the engine itself when the term
 * runs out, reaches the board.
 */
public class ApprovalsDesk implements Approver, AgentEventListener {

  private final PendingApprovalsRepository repository;
  private final Clock clock;

  public ApprovalsDesk(PendingApprovalsRepository repository, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Awaited<ApprovalResult> approve(ApprovalRequest request) {
    repository.asked(
        new PendingApproval(
            request.callId(),
            request.agentType(),
            request.agentId(),
            request.toolName().value(),
            request.action(),
            request.askedAt(),
            request.deadline(),
            request.replyToken().value(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    return Awaited.deferred();
  }

  @Override
  public void on(AgentType agentType, AgentId agentId, AgentEvent event) {
    switch (event) {
      case AgentEvent.CallApproved(var callId) ->
          repository.answered(agentType, agentId, callId, "approved", null, clock.instant());
      case AgentEvent.CallDenied(var callId, String reason) ->
          repository.answered(agentType, agentId, callId, "denied", reason, clock.instant());
      default -> {
        // Only decisions change the board.
      }
    }
  }
}
