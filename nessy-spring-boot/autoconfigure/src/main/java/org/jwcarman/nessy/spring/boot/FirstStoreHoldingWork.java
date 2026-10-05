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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.backend.event.AgentEvents;

/**
 * Agent work over several doors, each agent's status read from the door whose store holds it.
 *
 * <p>The door is the first that holds any event for the agent, as {@link FirstStoreHoldingStories}
 * chooses, and an agent no door holds is answered by the first door, which reports it idle. The
 * door is looked up on each call, so an agent whose first event is written later is found by the
 * next call.
 *
 * <p>Waiting approvals are the doors' together, in the order the doors were given. A door that
 * parks nothing, as the direct door does, contributes none.
 */
final class FirstStoreHoldingWork implements AgentWork {

  /** One door's store, and the work that reads it. */
  record Door(AgentEvents events, AgentWork work) {}

  private final List<Door> doors;

  FirstStoreHoldingWork(List<Door> doors) {
    if (doors.isEmpty()) {
      throw new IllegalArgumentException("at least one door is required");
    }
    this.doors = List.copyOf(doors);
  }

  @Override
  public AgentStatus status(AgentType type, AgentId id) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(id, "id must not be null");
    return doors.stream()
        .filter(door -> !door.events().readWrittenFrom(type, id, Seq.NONE, 1).isEmpty())
        .findFirst()
        .orElse(doors.getFirst())
        .work()
        .status(type, id);
  }

  @Override
  public List<ApprovalRequest> waitingApprovals() {
    List<ApprovalRequest> all = new ArrayList<>();
    doors.forEach(door -> all.addAll(door.work().waitingApprovals()));
    return List.copyOf(all);
  }

  @Override
  public List<ApprovalRequest> waitingApprovals(AgentType type) {
    Objects.requireNonNull(type, "type must not be null");
    List<ApprovalRequest> all = new ArrayList<>();
    doors.forEach(door -> all.addAll(door.work().waitingApprovals(type)));
    return List.copyOf(all);
  }
}
