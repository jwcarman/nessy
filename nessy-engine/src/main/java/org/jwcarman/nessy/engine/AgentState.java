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
package org.jwcarman.nessy.engine;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.backlog.BacklogItem;

/**
 * Everything an agent persists, and no more: what is waiting, what is being worked on, and whether
 * a turn is running.
 *
 * <p><b>What is deliberately absent.</b> No phase, no tool call records, no transcript. The turn
 * owns its own document, so a turn advancing eight tool calls rewrites THIS document zero times —
 * it is rewritten only when the backlog changes. That is what keeps an agent's state independent of
 * how much work it has done.
 *
 * <p><b>Why {@code inFlight} is a slot rather than the head of the list.</b> The coalescer sees the
 * WAITING items only. A policy that supersedes on a key would otherwise merge away the very
 * observation a turn is running on, and the turn would finish by discarding an item that is no
 * longer the one it processed. {@link #taking()} moves head to slot in ONE durable write, so the
 * observation is in the backlog or in flight, never neither.
 *
 * <p>{@code agentType} rides along because the serializer needs it: a manifest is produced from the
 * state alone, and an idle agent has no observation to infer a type from.
 *
 * @param <O> the observation type
 */
public record AgentState<O>(
    AgentType agentType, List<BacklogItem<O>> backlog, BacklogItem<O> inFlight, String turnId) {

  public AgentState {
    Objects.requireNonNull(agentType, "agentType must not be null");
    backlog = backlog == null ? List.of() : List.copyOf(backlog);
  }

  public static <O> AgentState<O> idle(AgentType agentType) {
    return new AgentState<>(agentType, List.of(), null, null);
  }

  /** Whether a turn is running. */
  public boolean busy() {
    return turnId != null;
  }

  /** Whether anything is waiting to become a turn. */
  public boolean hasWork() {
    return !backlog.isEmpty();
  }

  /** The backlog after {@code coalescer} decides what {@code arrival} does to it. */
  public AgentState<O> ingesting(BacklogCoalescer<O> coalescer, BacklogItem<O> arrival) {
    return new AgentState<>(agentType, coalescer.coalesce(backlog, arrival), inFlight, turnId);
  }

  /** Moves the head into flight and names the turn — ONE durable write. */
  public AgentState<O> taking(String newTurnId) {
    if (backlog.isEmpty()) {
      throw new IllegalStateException("nothing to take");
    }
    return new AgentState<>(
        agentType, backlog.subList(1, backlog.size()), backlog.getFirst(), newTurnId);
  }

  /** Back to rest: the turn is over and its observation is done with. */
  public AgentState<O> finished() {
    return new AgentState<>(agentType, backlog, null, null);
  }
}
