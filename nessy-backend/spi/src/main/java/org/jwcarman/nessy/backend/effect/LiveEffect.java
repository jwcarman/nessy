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
package org.jwcarman.nessy.backend.effect;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * One row of the effect table as it stands, read without claiming it.
 *
 * <p>Live means not finished: a finished row is deleted, so every row there is one an agent still
 * owes the outside world, whether it is waiting to be tried or has been tried and is being waited
 * on.
 *
 * @param effectId the row's own id
 * @param agentType the type of the agent that owes it
 * @param agentId the agent that owes it
 * @param effect what is owed, decoded
 * @param createdAt when the row was written
 * @param parkedAt when an attempt marked the row parked, if one did. A parked row keeps its mark
 *     until it is deleted, so a mark alone does not say the row is waiting now; see {@link
 *     #parkedNow}.
 * @param deadline when the row stops being worth waiting for
 * @param attemptsMade how many attempts have claimed it
 * @param running whether an attempt has claimed it and not finished
 */
public record LiveEffect(
    UUID effectId,
    AgentType agentType,
    AgentId agentId,
    AgentEffect effect,
    Instant createdAt,
    Optional<Instant> parkedAt,
    Instant deadline,
    int attemptsMade,
    boolean running) {

  /**
   * Whether this row is waiting on an answer at {@code now}: parked, claimed, and not yet at its
   * deadline.
   *
   * <p>A row parked and then claimed again at its deadline is still marked parked, and is running,
   * but its deadline is not after {@code now}: it is being given up on, not waited on.
   */
  public boolean parkedNow(Instant now) {
    return parkedAt.isPresent() && running && deadline.isAfter(now);
  }
}
