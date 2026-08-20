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

import java.util.List;
import java.util.Optional;

/**
 * The durable computation store (durable spec §9): slots with one PENDING→terminal flip, atomic
 * await, and opaque continuations. Implementations may not assume single-threaded callers — the
 * slot is the lock.
 */
public interface DurableComputationBackend {

  /**
   * Get-or-create; {@code created} is false when the slot already existed. Deterministic ids make
   * this the submit-once discipline's foundation (preamble ruling 4).
   */
  CreateResult create(ComputationId id);

  /**
   * Atomic (durable spec §12): EITHER the terminal outcome is returned, OR the continuation is
   * durably registered before completion can proceed. Registering an equal continuation twice is
   * one registration. Unknown id → {@link IllegalArgumentException}.
   */
  AwaitResult await(ComputationId id, Continuation continuation);

  /**
   * One flip (durable spec §10, §23, ruling 6): the first completion wins; every later attempt
   * returns {@link CompletionResult#ALREADY_TERMINAL} and changes nothing. An unknown id is created
   * already terminal — the deterministic address may travel before the slot exists, and the {@code
   * AlreadyCompleted} arm of {@link #await} absorbs completed-before-create.
   */
  CompletionResult complete(ComputationId id, Outcome outcome);

  /** Empty for a slot that was never created. */
  Optional<ComputationStatus> status(ComputationId id);

  /** Snapshot of registrations; the completing door feeds these to the dispatcher. */
  List<Continuation> continuationsOf(ComputationId id);
}
