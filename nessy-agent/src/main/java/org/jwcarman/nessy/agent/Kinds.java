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
 * The one place a kind-scoped keyspace name is composed (computation-identity spec §3): {@code
 * computation/<agentType>}, {@code approval/<agentType>}, {@code outbox/<agentType>}. Every caller
 * — {@link DeliveryWorker} in this package, and {@link
 * org.jwcarman.nessy.agent.host.HarnessConfig}/ {@link org.jwcarman.nessy.agent.host.Nessy}
 * building a harness's two backends — derives its kind through here, never by hand, so the three
 * strings can never drift apart. Isolation across agent types is by construction (spec §3): two
 * harnesses of different types over one substrate never share a kind.
 *
 * <p>Public: {@code HarnessConfig} and {@code Nessy} are the callers outside this package, deriving
 * the same three kinds from the same {@link AgentType} when they build a harness's two backends —
 * not application vocabulary, just the shared derivation both sides of the harness-construction
 * seam must agree on byte-for-byte.
 */
public final class Kinds {

  private static final String COMPUTATION_PREFIX = "computation/";
  private static final String APPROVAL_PREFIX = "approval/";
  private static final String OUTBOX_PREFIX = "outbox/";

  private Kinds() {}

  public static String computation(AgentType type) {
    return COMPUTATION_PREFIX + type.name();
  }

  public static String approval(AgentType type) {
    return APPROVAL_PREFIX + type.name();
  }

  public static String outbox(AgentType type) {
    return OUTBOX_PREFIX + type.name();
  }
}
