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

import java.util.Objects;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ComputationId;

/**
 * The approve/deny door (continuum-adoption spec §3, §7), addressed by the computation's own opaque
 * identity — the desk holds no state of its own, because the approval kind's own {@link
 * ContinuumClient} is the state. Complete, then nudge the delivery worker: an already-completed or
 * genuinely absent id (one that parses as a UUID but names no live computation) is equally benign
 * under at-least-once delivery — there is no "already decided" to refuse loudly, because there is
 * nothing left to read once the answer has transferred to its delivery. A structurally malformed id
 * (one whose {@code value()} is not a UUID at all) is a caller bug, not a benign race, and is NOT
 * swallowed: {@link #approve(ComputationId)}/{@link #deny(ComputationId, String)} throw {@link
 * IllegalArgumentException} for it (via {@link ContinuumIds#continuumId}) before ever reaching
 * Continuum.
 *
 * <p>No adapter type sits between this desk and Continuum (spec §9): {@link ContinuumClient} is the
 * wrapper {@code SubstrateComputations} used to be, so this desk holds one directly.
 */
public final class ApprovalDesk {

  private final ContinuumClient<Decision, Routing> client;
  private final Runnable nudge;

  /**
   * @param client the approval kind's Continuum client
   * @param nudge run after every decision, so it folds promptly rather than waiting on the next
   *     heartbeat sweep
   */
  public ApprovalDesk(ContinuumClient<Decision, Routing> client, Runnable nudge) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.nudge = Objects.requireNonNull(nudge, "nudge must not be null");
  }

  /**
   * @param id the approval's own opaque id
   * @throws IllegalArgumentException if {@code id.value()} does not parse as a UUID — Continuum's
   *     own id shape, and every id this desk ever mints one of
   */
  public void approve(ComputationId id) {
    decide(id, Decision.allow());
  }

  /**
   * @param id the approval's own opaque id
   * @param reason why — folds into the tool call's in-band failure so the model reads it
   * @throws IllegalArgumentException if {@code id.value()} does not parse as a UUID — Continuum's
   *     own id shape, and every id this desk ever mints one of
   */
  public void deny(ComputationId id, String reason) {
    decide(id, new Decision.Deny(reason));
  }

  private void decide(ComputationId id, Decision decision) {
    Objects.requireNonNull(id, "id must not be null");
    client.complete(ContinuumIds.continuumId(id.value()), decision);
    nudge.run();
  }
}
