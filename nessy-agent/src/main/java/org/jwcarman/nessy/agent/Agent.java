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
   * <p><b>Delivered roster:</b> {@code observer} receives every event the model- and tool-call
   * executors and the fold narrate for this id — {@code TextDelta}, {@code ThinkingDelta}, {@code
   * RedactedThinking}, {@code ToolCallRequested}, {@code ToolCallCompleted}, {@code
   * ToolCallProgressed}, {@code AssistantSaid}, and {@code TurnEnded} (front-ends spec §1, Task 3:
   * the last two now ride this same channel — a single path, not a second one alongside it). {@link
   * #ask} is built on exactly this door: it subscribes its own capture, {@code tell}s, and resolves
   * a {@link TurnOutcome} from {@code AssistantSaid}/{@code TurnEnded} alone.
   */
  Subscription subscribe(TurnObserver observer);

  /**
   * The pattern over the plain API above, not new machinery (front-ends spec §1): subscribe, tell,
   * block for the turn's own outcome, close — see {@link DefaultAgent#ask} for the exact mechanics.
   * {@code Replied} carries the assistant's final text; {@code Parked} carries the §5a {@link
   * org.jwcarman.nessy.spi.approval.ApprovalRequest} the turn suspended on; {@code Failed} carries
   * {@code TurnEnded}'s own reason.
   */
  TurnOutcome ask(O observation);
}
