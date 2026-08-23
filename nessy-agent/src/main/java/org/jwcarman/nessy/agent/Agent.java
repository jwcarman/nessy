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

import org.jwcarman.nessy.api.turn.Subscription;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * The whole public API (§3): observations in, progress on demand. The continuation door is not here
 * — executors hold a Sink from construction, and fabricating a completion is not expressible in an
 * application's vocabulary.
 */
public interface Agent<O> {

  /** Enqueue one ambient world fact; the backlog coalesces however it likes (§3.3). */
  void tell(O observation);

  /** Make this scope make progress: drain at Idle, re-fire when stale, else nothing (§6.1). */
  void drive();

  /**
   * Subscribes {@code observer} to this id's turns from here on (front-ends spec §2): a synchronous
   * {@link #tell}/{@link #drive} already in flight and a delivery folding on the harness's own
   * worker days later both reach it — the fanout lives inside the harness, scoped to this id, not
   * on this thin handle. Close the returned {@link Subscription} to stop listening; dropping it
   * unclosed leaks one routing entry, never a thread.
   *
   * <p><b>Delivered roster (not yet the whole grammar):</b> {@code observer} currently receives
   * {@code TextDelta}, {@code ThinkingDelta}, {@code RedactedThinking}, {@code ToolCallRequested},
   * {@code ToolCallCompleted}, and {@code ToolCallProgressed} — the events the model- and tool-call
   * executors narrate directly. {@code AssistantSaid} and {@code TurnEnded} do NOT ride this
   * channel yet; they still narrate only through the harness's separate, id-free {@code
   * AgentObserver} wiring. Widening this to include them is later front-ends work (the {@code ask}
   * pattern needs them), not something this method does today.
   */
  Subscription subscribe(TurnObserver observer);
}
