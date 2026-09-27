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
package org.jwcarman.nessy.engine.core;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.event.AgentEvent;

/**
 * What a command came to: facts to record, and work to do.
 *
 * <p><b>It does not carry the resulting state.</b> That would be a second way to compute something
 * {@link AgentState#applyAll} already computes, and two ways to reach one answer eventually
 * disagree -- with the disagreement surfacing on a later reconstitution, far from whatever caused
 * it. Applying the events is the only way state changes, so the events are definitively the truth.
 *
 * <p>A harness that wants the new state derives it: {@code state.applyAll(decision.events())}.
 */
public sealed interface Decision {

  /** The facts, in the order they happened. */
  List<AgentEvent> events();

  /** The work to do, once the facts are durable. */
  List<AgentEffect> effects();

  /** Something happened. */
  record Advance(List<AgentEvent> events, List<AgentEffect> effects) implements Decision {
    public Advance {
      events = List.copyOf(events);
      effects = List.copyOf(effects);
    }
  }

  /**
   * Nothing happened, and nothing should be written down.
   *
   * <p>A declined command, a redelivered outcome, a second {@code Terminate}. Recording any of
   * these would be claiming something happened when nothing did -- and a harness that polls would
   * fill the stream with the answer "no".
   */
  record Ignore() implements Decision {
    @Override
    public List<AgentEvent> events() {
      return List.of();
    }

    @Override
    public List<AgentEffect> effects() {
      return List.of();
    }
  }

  static Decision ignore() {
    return new Ignore();
  }

  static Decision of(List<AgentEvent> events, List<AgentEffect> effects) {
    return new Advance(events, effects);
  }

  /**
   * The same decision with facts in front of it.
   *
   * <p>For the one case where a command settles something that happened over several tries: the
   * tries are written down before the event that closes the work. Prepended rather than appended
   * because they happened first, and a story out of order is worse than no story.
   *
   * <p>An {@link Ignore} stays ignored, so that a decision to record nothing cannot be turned into
   * a decision to record something. <b>This is not what stops a redelivery writing its attempts
   * twice</b> -- that is settled a level up, where every state but the one mid-inference answers a
   * completion with {@code ignore()} before the attempts are so much as assembled. This branch is
   * unreachable today and kept so the method's contract does not depend on that staying true.
   */
  default Decision prepend(List<AgentEvent> earlier) {
    if (earlier.isEmpty() || this instanceof Ignore) {
      return this;
    }
    List<AgentEvent> all = new ArrayList<>(earlier);
    all.addAll(events());
    return new Advance(all, effects());
  }
}
