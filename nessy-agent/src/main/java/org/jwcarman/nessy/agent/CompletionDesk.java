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
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The result door (continuum-adoption spec §3, §5): completes tool computations — "what did it
 * return?" — with a {@code ToolResult}, addressed by the call's own Continuum-minted identity.
 * Complete, then nudge the delivery worker, exactly as {@link ApprovalDesk} does; see its javadoc
 * for why a second completion is not refused loudly.
 *
 * <p>No adapter type sits between this desk and Continuum (spec §9): {@link ContinuumClient} is the
 * wrapper {@code SubstrateComputations} used to be, so this desk holds one directly.
 */
public final class CompletionDesk {

  private final ContinuumClient<ToolResult, Routing> client;
  private final Runnable nudge;

  /**
   * @param client the tool kind's Continuum client
   * @param nudge run after every decision, so it folds promptly rather than waiting on the next
   *     heartbeat sweep
   */
  public CompletionDesk(ContinuumClient<ToolResult, Routing> client, Runnable nudge) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.nudge = Objects.requireNonNull(nudge, "nudge must not be null");
  }

  /**
   * @param id the tool computation's own opaque id
   * @param result what the tool returned
   * @throws IllegalArgumentException if {@code id.value()} does not parse as a UUID — Continuum's
   *     own id shape, and every id this desk ever mints one of
   */
  public void complete(ComputationId id, ToolResult result) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(result, "result must not be null");
    client.complete(ContinuumIds.continuumId(id.value()), result);
    nudge.run();
  }

  /**
   * @param id the tool computation's own opaque id
   * @param reason why — folds into the tool call's in-band failure so the model reads it
   * @throws IllegalArgumentException if {@code id.value()} does not parse as a UUID — Continuum's
   *     own id shape, and every id this desk ever mints one of
   */
  public void fail(ComputationId id, String reason) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    client.fail(ContinuumIds.continuumId(id.value()), reason);
    nudge.run();
  }
}
