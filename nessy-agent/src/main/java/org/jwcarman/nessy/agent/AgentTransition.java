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
package org.jwcarman.nessy.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.message.Message;

/**
 * What one event decides: what to become, what to commit to history, what to fire. Returned as a
 * value so that I/O is structurally impossible inside a phase (spec §2.5).
 */
public record AgentTransition(AgentPhase next, List<Message> commit, List<Effect> effects) {

  private static final AgentTransition IGNORED = new AgentTransition();

  public AgentTransition {
    Objects.requireNonNull(next, "next must not be null");
    commit = List.copyOf(commit);
    effects = List.copyOf(effects);
  }

  /** Private ignored-marker constructor; bypasses the canonical null check via a sentinel. */
  private AgentTransition() {
    this(AgentPhase.SENTINEL, List.of(), List.of());
  }

  public static AgentTransition to(AgentPhase next, Effect... effects) {
    return new AgentTransition(next, List.of(), List.of(effects));
  }

  /** A stale or duplicate event: fold nothing, commit nothing, fire nothing (spec §2.2). */
  public static AgentTransition ignore() {
    return IGNORED;
  }

  public AgentTransition commit(Message... messages) {
    requireNotIgnored();
    var all = new ArrayList<>(commit);
    all.addAll(List.of(messages));
    return new AgentTransition(next, all, effects);
  }

  public AgentTransition emit(List<Effect> more) {
    requireNotIgnored();
    var all = new ArrayList<>(effects);
    all.addAll(more);
    return new AgentTransition(next, commit, all);
  }

  public boolean isIgnored() {
    return this == IGNORED;
  }

  @Override
  public AgentPhase next() {
    requireNotIgnored();
    return next;
  }

  private void requireNotIgnored() {
    if (isIgnored()) {
      throw new IllegalStateException("an ignored transition decides nothing");
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (isIgnored() || !(o instanceof AgentTransition other) || other.isIgnored()) {
      return false;
    }
    return Objects.equals(next, other.next)
        && Objects.equals(commit, other.commit)
        && Objects.equals(effects, other.effects);
  }

  @Override
  public int hashCode() {
    return isIgnored() ? System.identityHashCode(this) : Objects.hash(next, commit, effects);
  }

  @Override
  public String toString() {
    if (isIgnored()) {
      return "AgentTransition[ignored]";
    }
    return "AgentTransition[next=" + next + ", commit=" + commit + ", effects=" + effects + "]";
  }
}
