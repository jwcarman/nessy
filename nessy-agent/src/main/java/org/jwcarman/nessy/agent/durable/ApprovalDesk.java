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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.CompletionResult;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.ContinuationDispatcher;
import org.jwcarman.nessy.durable.DurableComputationBackend;
import org.jwcarman.nessy.durable.Outcome;

/**
 * The approve/deny doors (§4.3): a token is the completion capability, resolved here to its slot.
 * Complete-then-fire, at-least-once — a crash between the flip and the fire is covered by the lazy
 * re-drive floor (plan decision 3). Unknown tokens and second decisions are refused loudly.
 */
public final class ApprovalDesk {

  private final DurableComputationBackend backend;
  private final ContinuationDispatcher dispatcher;
  private final ConcurrentMap<ParkToken, ComputationId> byToken = new ConcurrentHashMap<>();

  public ApprovalDesk(DurableComputationBackend backend, ContinuationDispatcher dispatcher) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
  }

  /**
   * Idempotent: registering the same token for the same slot twice is one registration. Rebinding
   * an already-registered token to a different slot is refused loudly rather than silently
   * discarded.
   */
  public void register(ParkToken token, ComputationId id) {
    Objects.requireNonNull(token, "token must not be null");
    Objects.requireNonNull(id, "id must not be null");
    ComputationId prior = byToken.putIfAbsent(token, id);
    if (prior != null && !prior.equals(id)) {
      throw new IllegalStateException("token already bound to a different computation");
    }
  }

  public void approve(ParkToken token, ToolResult result) {
    Objects.requireNonNull(result, "result must not be null");
    decide(token, new Outcome.Success(result));
  }

  public void deny(ParkToken token, String reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    decide(token, new Outcome.Failure(reason));
  }

  /**
   * A handler throw here leaves the token retired and the slot terminal — the lazy re-drive floor
   * (plan decision 3) covers delivery; the Plan-5 outbox is the prompt-delivery upgrade.
   */
  private void decide(ParkToken token, Outcome outcome) {
    Objects.requireNonNull(token, "token must not be null");
    ComputationId id = byToken.get(token);
    if (id == null) {
      throw new IllegalArgumentException("unknown or already-decided token");
    }
    if (backend.complete(id, outcome) == CompletionResult.ALREADY_TERMINAL) {
      throw new IllegalStateException("already decided: " + id.value());
    }
    dispatcher.fire(backend.continuationsOf(id), outcome);
    byToken.values().removeIf(id::equals);
  }
}
