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
import org.jwcarman.nessy.api.tool.ApprovalRequest;

/**
 * Where the work of agents is read.
 *
 * <p>Every answer is computed from what is stored, on every call. Nothing here is remembered
 * between calls, so it is the same from any process and after a restart. It runs no transaction and
 * takes no lock, so it may be a step behind an agent that is moving.
 */
public interface AgentWork {

  /** What one agent is doing; an agent nobody has told anything is idle with nothing queued. */
  AgentStatus status(AgentType type, AgentId id);

  /** The approval requests every agent is waiting on. */
  List<ApprovalRequest> waitingApprovals();

  /** The approval requests the agents of one type are waiting on. */
  List<ApprovalRequest> waitingApprovals(AgentType type);
}
