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
package org.jwcarman.nessy.agent.durable;

import java.util.Objects;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.DurableComputationBackend;
import org.jwcarman.nessy.durable.Outcome;

/**
 * The approve/deny door (durable-deliveries spec §5), addressed by the computation's own
 * deterministic identity — the desk holds no state of its own, because the backend is the state.
 * Complete, then nudge the delivery worker: a completed-or-absent id is equally benign under
 * at-least-once delivery (ruling 6, reversed) — there is no "already decided" to refuse loudly,
 * because there is nothing left to read once the answer has transferred to its delivery.
 */
public final class ApprovalDesk {

  private final DurableComputationBackend backend;
  private final Runnable nudge;

  public ApprovalDesk(DurableComputationBackend backend, Runnable nudge) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.nudge = Objects.requireNonNull(nudge, "nudge must not be null");
  }

  /**
   * The decision is a fact: {@code Success(Decision)} — answering "no" is a successful
   * adjudication.
   */
  public void approve(ComputationId id) {
    decide(id, DurableDecisions.granted());
  }

  public void deny(ComputationId id, String reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    decide(id, DurableDecisions.denied(reason));
  }

  private void decide(ComputationId id, Outcome outcome) {
    Objects.requireNonNull(id, "id must not be null");
    backend.complete(id, outcome);
    nudge.run();
  }
}
