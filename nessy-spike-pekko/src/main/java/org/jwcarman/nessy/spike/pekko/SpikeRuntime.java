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

import java.time.Duration;
import org.apache.pekko.actor.typed.ActorSystem;

/**
 * THROWAWAY SPIKE. A place to run agents. Implemented twice — once on a single node with {@link
 * SpikeRegistry}, once on Cluster Sharding — over the same {@link AgentActor}.
 */
public interface SpikeRuntime extends AutoCloseable {

  /** The only door: tell an agent id a command. */
  SpikeAgents agents();

  /** For tests that need a TestProbe; not part of the runtime's own job. */
  ActorSystem<?> system();

  /** How long the runtime took to become usable. */
  Duration startupTime();

  /** A real termination: the actor system is gone before this returns. */
  @Override
  void close();
}
