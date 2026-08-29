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
package org.jwcarman.nessy.engine;

import org.jwcarman.nessy.api.agent.AgentType;

/**
 * A running set of agents of one type: tell them what happened, and shut them down.
 *
 * <p>Nothing on this door mentions Pekko. A caller hands {@link HarnessFactory} an {@code
 * ActorSystem} once and then never says the word again — whether an observation crosses a mailbox,
 * a cluster shard, or neither is the engine's business (engine-extraction spec §11).
 *
 * <p><b>Destination, not final address.</b> This lives in {@code nessy-engine} rather than {@code
 * nessy-api} because moving it up is blocked behind vocabulary still tangled with the engine being
 * deleted (spec §8). {@code nessy-agent} has its own {@code Harness} until then. The two are not
 * the same design written twice: this one names no {@code ApprovalDesk} or {@code CompletionDesk},
 * because an actor approval goes through the {@code Approver} and its own actor (composition spec
 * §7). Spec §8.3 lists the merge as debt to pay once {@code nessy-agent} is gone.
 *
 * @param <O> the observation type these agents accept
 */
public interface Harness<O> {

  /** The type every agent here carries — the persistence prefix and kind-name root. */
  AgentType type();

  /**
   * Something happened; tell the agent named {@code agentId}.
   *
   * <p>Returns as soon as the observation is durably the agent's problem, NOT when a turn finishes.
   * A turn may start now, or after the one already running, or never if a coalescer supersedes this
   * observation with a later one (composition spec §4). An agent that does not exist yet is
   * created.
   */
  void observe(String agentId, O observation);

  /**
   * Infrastructure-only: stops what this harness started and nothing else.
   *
   * <p><b>Never terminates the {@code ActorSystem}</b> — that belongs to whoever handed it over,
   * and a harness that kills its caller's system is a very unfun bug to find inside a Boot app
   * (spec §3.1). Deliberately not {@link AutoCloseable}: nothing reaches for this by accident
   * through try-with-resources.
   */
  void shutdown();
}
