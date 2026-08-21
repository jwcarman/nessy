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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentWiring;
import org.jwcarman.nessy.agent.DefaultAgent;
import org.jwcarman.nessy.agent.durable.ApprovalDesk;
import org.jwcarman.nessy.agent.durable.CompletionDesk;

/**
 * The long-running door (§7.1, §4.3 second-wave amendment): many scopes, one process, one shared
 * backend behind the two desks. Per-id worlds — store, memory, backlog — are cached here and
 * survive across resolves; the {@link DefaultAgent} wrapper handed back is fresh every time (the
 * transient-instance model, §4.3). The per-scope world cache grows monotonically for the host's
 * lifetime — one entry per {@link AgentId} ever posted to — and nothing evicts it; eviction is a
 * future seam.
 */
public final class AutonomousHost implements AutoCloseable {

  private final ExecutorService owned;
  private final ApprovalDesk approvals;
  private final CompletionDesk completions;
  private final Function<AgentId, AgentWiring<String>> wirings;
  private final ConcurrentMap<AgentId, AgentWiring<String>> cache = new ConcurrentHashMap<>();

  /**
   * {@code owned} is null when the caller supplied its own executor — {@link #close()} then does
   * nothing.
   */
  AutonomousHost(
      ExecutorService owned,
      ApprovalDesk approvals,
      CompletionDesk completions,
      Function<AgentId, AgentWiring<String>> wirings) {
    this.owned = owned;
    this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
    this.completions = Objects.requireNonNull(completions, "completions must not be null");
    this.wirings = Objects.requireNonNull(wirings, "wirings must not be null");
  }

  /** One observation through the front door; the scope drains it at Idle (spec §3.3). */
  public void post(String agentId, String text) {
    agentFor(AgentId.of(agentId)).observe(text);
  }

  public ApprovalDesk approvals() {
    return approvals;
  }

  public CompletionDesk completions() {
    return completions;
  }

  DefaultAgent<String> agentFor(AgentId id) {
    return new DefaultAgent<>(cache.computeIfAbsent(id, wirings));
  }

  @Override
  public void close() {
    if (owned != null) {
      owned.close();
    }
  }
}
