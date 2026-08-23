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
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The result door (durable-deliveries spec §5): completes {@code tool:} computations — "what did it
 * return?" — with a {@code ToolResult}, addressed by the call's own deterministic identity.
 * Complete, then nudge the delivery worker, exactly as {@link ApprovalDesk} does; see its javadoc
 * for why a second completion is not refused loudly.
 *
 * <p>This backend is the execution-kind instance (computation-identity spec §3) — {@code
 * computation/&lt;agentType&gt;} — never the approval one {@link ApprovalDesk} holds.
 */
public final class CompletionDesk {

  private final SubstrateComputations backend;
  private final Runnable nudge;

  public CompletionDesk(SubstrateComputations backend, Runnable nudge) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.nudge = Objects.requireNonNull(nudge, "nudge must not be null");
  }

  public void complete(ComputationId id, ToolResult result) {
    Objects.requireNonNull(result, "result must not be null");
    finish(id, new Outcome.Success(backend.encodeSuccess(result)));
  }

  public void fail(ComputationId id, String reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    finish(id, new Outcome.Failure(reason));
  }

  private void finish(ComputationId id, Outcome outcome) {
    Objects.requireNonNull(id, "id must not be null");
    backend.complete(id, outcome);
    nudge.run();
  }
}
