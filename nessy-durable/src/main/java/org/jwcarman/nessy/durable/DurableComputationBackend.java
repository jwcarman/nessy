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
package org.jwcarman.nessy.durable;

import java.time.Instant;
import java.util.Optional;

/**
 * The durable computation store: ownership transfer, not waiting (durable-deliveries spec §3).
 * Presence means pending — there is no status field and no terminal record. Implementations may not
 * assume single-threaded callers.
 *
 * <p>Internal vocabulary and the override seam for a genuinely foreign engine (Restate, Temporal);
 * nobody implements this seam just to get a database. The default implementation is the substrate's
 * {@code computation} recipe, riding the same {@code Substrate} every other recipe does.
 */
public interface DurableComputationBackend {

  /**
   * Get-or-create; {@code created} is false when the computation already existed. The return
   * address is durable before any dispatch — the register-after-create window is unexpressible
   * (spec §3). Deterministic ids make this the submit-once discipline's foundation.
   */
  CreateResult create(
      ComputationId id,
      ToolInvocationId invocation,
      Continuation returnAddress,
      Optional<Instant> deadline);

  /**
   * One atomic ownership transfer (spec §3, §7 invariant 5): DELETE the computation and CREATE its
   * outbox delivery, or do nothing. {@link CompletionResult#TRANSFERRED} means this call performed
   * that transfer; {@link CompletionResult#ALREADY_DONE} means the computation was absent — a
   * benign, ignorable outcome under at-least-once delivery (ruling 6, reversed: completion never
   * creates records).
   */
  CompletionResult complete(ComputationId id, Outcome outcome);

  /** Empty for a computation that is not currently pending — completed, or never created. */
  Optional<PendingComputation> find(ComputationId id);
}
