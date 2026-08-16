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
package org.jwcarman.nessy;

import java.util.Objects;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationSnapshot;

/**
 * The narrow doors view onto a child agent, returned by {@link Agent#subagent(String)} (design of
 * record 2026-08-16 §0, ruling 3): {@link #approve}, {@link #deny}, {@link #resume}, {@link
 * #snapshot}, and {@link #subagent(String)} for further tree traversal. Every door delegates
 * straight to the same-named door on the underlying child {@link Agent}.
 *
 * <p>Deliberately narrow: there is no {@code converse()} or {@code tell()} here. A subagent's own
 * conversations exist only through delegation — the parent's own delegation tool call is what tells
 * a subagent anything; this handle exists only to answer the callbacks a parked or completed child
 * conversation still needs answered from the outside (an approval, a denial, a resolved wait, a
 * page rebuild). The absent methods are the test: no amount of assertion proves a method does not
 * compile, so the proof lives in the type itself, not in a unit test.
 */
public final class Subagent {

  private final Agent<String> agent;

  Subagent(Agent<String> agent) {
    this.agent = Objects.requireNonNull(agent, "agent must not be null");
  }

  /** This subagent's own name — the durable stamp its own parks carry. */
  public String name() {
    return agent.name();
  }

  /** {@link Agent#approve(ParkToken)} on the underlying child. */
  public RunOutcome approve(ParkToken token) {
    return agent.approve(token);
  }

  /** {@link Agent#deny(ParkToken, String)} on the underlying child. */
  public RunOutcome deny(ParkToken token, String reason) {
    return agent.deny(token, reason);
  }

  /** {@link Agent#resume(ParkToken, ToolResolution)} on the underlying child. */
  public RunOutcome resume(ParkToken token, ToolResolution resolution) {
    return agent.resume(token, resolution);
  }

  /** {@link Agent#snapshot(ConversationId)} on the underlying child. */
  public ConversationSnapshot snapshot(ConversationId id) {
    return agent.snapshot(id);
  }

  /**
   * Tree traversal: this subagent's own child, named {@code name} — {@link Agent#subagent(String)}
   * on the underlying child, so a grandchild's doors are reachable as {@code
   * writer.subagent("researcher").subagent("archivist")}.
   *
   * @throws IllegalArgumentException if this subagent has no child named {@code name}
   */
  public Subagent subagent(String name) {
    return agent.subagent(name);
  }
}
