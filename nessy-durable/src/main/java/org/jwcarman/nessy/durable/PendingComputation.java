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
import java.util.Objects;
import java.util.Optional;

/**
 * The whole of what "presence means pending" stores (durable-deliveries spec §3): the invocation
 * this computation is doing work for, the durable reply-to address stamped at creation, and an
 * optional deadline. There is no status field and no outcome — presence alone is the pending
 * signal; the moment the work completes, {@code complete} deletes this and births the outbox
 * delivery that carries the result onward (spec §4).
 */
public record PendingComputation(
    ComputationId id,
    ToolInvocationId invocation,
    Continuation returnAddress,
    Optional<Instant> deadline) {

  public PendingComputation {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(invocation, "invocation must not be null");
    Objects.requireNonNull(returnAddress, "returnAddress must not be null");
    Objects.requireNonNull(deadline, "deadline must not be null");
  }
}
