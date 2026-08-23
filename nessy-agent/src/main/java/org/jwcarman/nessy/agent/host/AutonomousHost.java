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
package org.jwcarman.nessy.agent.host;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import org.jwcarman.nessy.agent.Agent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.durable.ApprovalDesk;
import org.jwcarman.nessy.agent.durable.CompletionDesk;

/**
 * The long-running door (§7.1): a thin delegating shim over its {@link Harness}, which now owns
 * every piece of life-support this class used to build (harness-first spec §4) — the delivery
 * worker, the desks, the reaper. {@code post} is bind-plus-observe; {@code approvals}/{@code
 * completions} forward to the harness's own doors; {@link #close()} is the harness's {@link
 * Harness#shutdown()}. This class survives Task 1 only as this shim; its deletion is a later task's
 * job (harness-first plan) once callers migrate to {@code harness.bind(id).observe(...)} directly.
 */
public final class AutonomousHost<O> implements AutoCloseable {

  private final ExecutorService owned;
  private final Harness<O> harness;

  /**
   * {@code owned} is null when the caller supplied its own executor — {@link #close()} then does
   * nothing to it (the harness's own worker heartbeat is always stopped, regardless).
   */
  AutonomousHost(ExecutorService owned, Harness<O> harness) {
    this.owned = owned;
    this.harness = Objects.requireNonNull(harness, "harness must not be null");
  }

  /** One observation through the front door; the scope drains it at Idle (spec §3.3). */
  public void post(String agentId, O observation) {
    agentFor(AgentId.of(agentId)).observe(observation);
  }

  public ApprovalDesk approvals() {
    return harness.approvals();
  }

  public CompletionDesk completions() {
    return harness.completions();
  }

  Agent<O> agentFor(AgentId id) {
    return harness.bind(id);
  }

  @Override
  public void close() {
    harness.shutdown();
    if (owned != null) {
      owned.close();
    }
  }
}
