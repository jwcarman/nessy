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
package org.jwcarman.nessy.spike.pekko;

/**
 * THROWAWAY SPIKE. The entire difference between running on one node and running on a cluster.
 *
 * <p>Three lines. That is the round-3 thesis made testable: {@link AgentActor} names no cluster
 * type, so the only thing a sharded deployment changes is how you get from an agent id to something
 * you can tell a message to — an {@code ActorRef} handed out by {@link SpikeRegistry}, or an {@code
 * EntityRef} handed out by {@code ClusterSharding}. Both are "tell this id a command".
 *
 * <p>The same test contract runs against both implementations. If that passes, "the runtime is
 * swappable" is demonstrated rather than asserted.
 */
public interface SpikeAgents {

  /** Fire-and-forget to one agent, creating it if this is the first anyone has heard of it. */
  void tell(String agentId, AgentActor.Command command);
}
