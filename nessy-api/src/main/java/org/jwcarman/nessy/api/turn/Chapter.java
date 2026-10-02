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
package org.jwcarman.nessy.api.turn;

import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;

/**
 * A closed run of one agent's turns: every turn from {@code from} through {@code through}, both
 * included.
 *
 * <p>The unit an agent's history is cut into. Chapters are contiguous, never overlap, and never
 * reach the turn in flight. Once closed a chapter does not change: its bounds are fixed, and what
 * stands in for it in the context is written once.
 *
 * <p><b>The range is whole turns.</b> A turn id is the seq of the input that opened it, so the
 * bounds are ordered but not consecutive. A chapter can never split a turn.
 *
 * @param agentType the type of the agent whose turns these are
 * @param agentId the agent
 * @param from the first turn of the chapter
 * @param through the last turn of the chapter, inclusive
 */
public record Chapter(AgentType agentType, AgentId agentId, TurnId from, TurnId through) {

  public Chapter {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(through, "through must not be null");
    if (through.value() < from.value()) {
      throw new IllegalArgumentException(
          "a chapter must run forwards: from %s through %s".formatted(from, through));
    }
  }

  /** Whether {@code turn} is one of this chapter's turns. */
  public boolean covers(TurnId turn) {
    return turn.value() >= from.value() && turn.value() <= through.value();
  }
}
