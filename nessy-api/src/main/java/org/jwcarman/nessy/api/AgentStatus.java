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
package org.jwcarman.nessy.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.tool.ApprovalRequest;

/**
 * What one agent is doing right now, read from what is stored about it.
 *
 * @param activity the one word for it
 * @param queued how many inputs it has been told and has not started; always zero on the direct
 *     door, which keeps no queue
 * @param turn the turn in progress, empty when the agent is between turns or has ended
 * @param waitingApprovals the approval requests the agent is waiting on
 * @param waitingToolCalls how many tool calls the agent has put aside and is waiting on
 */
public record AgentStatus(
    Activity activity,
    int queued,
    Optional<TurnId> turn,
    List<ApprovalRequest> waitingApprovals,
    int waitingToolCalls) {

  /** The state an agent is in, in the words of someone watching it. */
  public enum Activity {
    /** Between turns, with nothing waiting to start. */
    IDLE,
    /** Moving: in a model call, running a tool, or about to start the next input. */
    WORKING,
    /** Everything it has outstanding is put aside, waiting on an answer from outside. */
    WAITING,
    /** Told to terminate and done; it takes nothing more. */
    ENDED
  }

  public AgentStatus {
    Objects.requireNonNull(activity, "activity must not be null");
    Objects.requireNonNull(turn, "turn must not be null");
    Objects.requireNonNull(waitingApprovals, "waitingApprovals must not be null");
    if (queued < 0) {
      throw new IllegalArgumentException("queued must not be negative, was " + queued);
    }
    if (waitingToolCalls < 0) {
      throw new IllegalArgumentException(
          "waitingToolCalls must not be negative, was " + waitingToolCalls);
    }
    waitingApprovals = List.copyOf(waitingApprovals);
  }
}
