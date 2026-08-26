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

import java.util.List;
import java.util.Objects;

/**
 * What one {@link ToolCallEvent} decides for one call (deferral-by-callback spec §6): either the
 * call moves — becoming a new {@link ToolCallState} and possibly asking for an effect — or the
 * event was not for it and is dropped.
 *
 * <p>A call's own decision, and nothing more: whether the TURN is now finished is the phase's
 * question, because no single call can see the others.
 */
public sealed interface ToolCallTransition {

  /** The call moved. */
  record Advanced(ToolCallState next, List<Effect> effects) implements ToolCallTransition {
    public Advanced {
      Objects.requireNonNull(next, "next must not be null");
      effects = List.copyOf(effects);
    }
  }

  /**
   * The event was not this call's to take — an orphan, a duplicate, or an id this state never
   * recorded. Permanent, every one of them (approval-lifecycle spec §4).
   *
   * <p>Reported as a value, never logged here: the reducer is pure, and the one WARN that names a
   * dropped delivery belongs to {@code DeliveryWorker}, which alone knows the agent, the call and
   * the computation the drop was about.
   */
  record Dropped() implements ToolCallTransition {}

  static ToolCallTransition to(ToolCallState next, Effect... effects) {
    return new Advanced(next, List.of(effects));
  }

  static ToolCallTransition dropped() {
    return new Dropped();
  }
}
