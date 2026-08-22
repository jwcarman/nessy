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
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.DefaultAgent;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.durable.ApprovalDesk;
import org.jwcarman.nessy.agent.durable.CompletionDesk;

/**
 * The long-running door (§7.1, §4.3 second-wave amendment): many scopes, one process, one shared
 * backend behind the two desks. There is no per-id cache — every {@link #agentFor(AgentId)} call
 * binds a fresh {@link Harness#bind(AgentId)} handle and hands back a fresh {@link DefaultAgent}
 * (the transient-instance model, §4.3); the shared substrate (Task 2) behind the harness's
 * memory/store/backlog factories is what makes a stateless resolve correct — two binds for the same
 * id see the same world.
 */
public final class AutonomousHost<O> implements AutoCloseable {

  private final ExecutorService owned;
  private final ApprovalDesk approvals;
  private final CompletionDesk completions;
  private final Harness<O> harness;

  /**
   * {@code owned} is null when the caller supplied its own executor — {@link #close()} then does
   * nothing.
   */
  AutonomousHost(
      ExecutorService owned,
      ApprovalDesk approvals,
      CompletionDesk completions,
      Harness<O> harness) {
    this.owned = owned;
    this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
    this.completions = Objects.requireNonNull(completions, "completions must not be null");
    this.harness = Objects.requireNonNull(harness, "harness must not be null");
  }

  /** One observation through the front door; the scope drains it at Idle (spec §3.3). */
  public void post(String agentId, O observation) {
    agentFor(AgentId.of(agentId)).observe(observation);
  }

  public ApprovalDesk approvals() {
    return approvals;
  }

  public CompletionDesk completions() {
    return completions;
  }

  DefaultAgent<O> agentFor(AgentId id) {
    return new DefaultAgent<>(harness, harness.bind(id));
  }

  @Override
  public void close() {
    if (owned != null) {
      owned.close();
    }
  }
}
