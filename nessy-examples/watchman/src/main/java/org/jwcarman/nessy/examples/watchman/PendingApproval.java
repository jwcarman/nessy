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

import java.time.Instant;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.tool.CallId;

/** One question put to a person, as the board keeps it. */
public record PendingApproval(
    CallId callId,
    AgentType agentType,
    AgentId agentId,
    String tool,
    String action,
    Instant askedAt,
    Instant expiresAt,
    String replyToken,
    Optional<String> answer,
    Optional<String> note,
    Optional<Instant> answeredAt) {

  public boolean waiting() {
    return answer.isEmpty();
  }
}
