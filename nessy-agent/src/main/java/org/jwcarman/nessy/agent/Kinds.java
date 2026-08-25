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

/**
 * The one place a kind-scoped name is composed (computation-identity spec §3; continuum-adoption
 * spec §3): {@code tool/<agentType>} and {@code approval/<agentType>}. Every caller — {@link
 * DeliveryWorker} in this package, and {@link org.jwcarman.nessy.agent.host.HarnessConfig}/{@link
 * org.jwcarman.nessy.agent.host.Nessy} building a harness's backends — derives its kind through
 * here, never by hand, so the strings can never drift apart. Isolation across agent types is by
 * construction: two harnesses of different types over one substrate never share a kind.
 *
 * <p>{@link #approval(AgentType)} and {@link #tool(AgentType)} both name Continuum {@code
 * ContinuumClient} kinds rather than {@code SubstrateComputations} ones (continuum-adoption spec
 * §3) — {@link #tool(AgentType)} (formerly {@code computation(AgentType)}) is the rename: it stops
 * naming Nessy's old Substrate-backed tool kind and starts naming the {@code
 * ContinuumClient<ToolResult, Routing>} kind.
 *
 * <p>Public: {@code HarnessConfig} and {@code Nessy} are the callers outside this package, deriving
 * the same kinds from the same {@link AgentType} when they build a harness's backends — not
 * application vocabulary, just the shared derivation both sides of the harness-construction seam
 * must agree on byte-for-byte.
 */
public final class Kinds {

  private static final String TOOL_PREFIX = "tool/";
  private static final String APPROVAL_PREFIX = "approval/";

  private Kinds() {}

  /**
   * The tool kind's own name (continuum-adoption spec §3): {@code tool/<agentType>}. Renamed from
   * {@code computation(AgentType)} — the tool kind now lives on a {@code
   * ContinuumClient<ToolResult, Routing>}, not a {@code SubstrateComputations}.
   *
   * @param type the agent type
   * @return the tool kind
   */
  public static String tool(AgentType type) {
    return TOOL_PREFIX + type.name();
  }

  public static String approval(AgentType type) {
    return APPROVAL_PREFIX + type.name();
  }
}
