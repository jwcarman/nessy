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
package org.jwcarman.nessy.spi.subagent;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;

/**
 * The parent-child correlation a subagent callback resumes against: which parent {@link ParkToken}
 * a child conversation's completion answers, and which agent minted that token — the same two facts
 * {@link org.jwcarman.nessy.Agent#resume} needs to route the answer home.
 *
 * <p>One link per child conversation, last write wins — a redelivered spawn rewrites the identical
 * link, the same replay-tolerance reasoning as {@link org.jwcarman.nessy.spi.plan.PlanStore}.
 */
public interface SubagentLinks {

  /**
   * One parent-child correlation: the token the parent is waiting on, and the name of the agent
   * that minted it — the stamp {@link org.jwcarman.nessy.Agent#resume} verifies before routing.
   *
   * @param parentToken the park the child's completion resumes
   * @param parentAgentName the name of the agent that minted {@code parentToken}
   */
  record Link(ParkToken parentToken, String parentAgentName) {

    public Link {
      Objects.requireNonNull(parentToken, "parentToken must not be null");
      Objects.requireNonNull(parentAgentName, "parentAgentName must not be null");
    }
  }

  /**
   * The link saved for {@code child}, or empty if none has been saved (or it was {@link #forget}).
   */
  Optional<Link> find(ConversationId child);

  /** Upserts the link for {@code child}, last write wins — a redelivered spawn rewrites it. */
  void save(ConversationId child, ParkToken parentToken, String parentAgentName);

  /** Removes the link for {@code child}; absent is a no-op (idempotent). */
  void forget(ConversationId child);

  /** The zero-configuration default: links live in this JVM and die with it. */
  static SubagentLinks inMemory() {
    return new InMemorySubagentLinks();
  }
}
