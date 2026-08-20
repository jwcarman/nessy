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
package org.jwcarman.nessy.agent.spi;

import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.Transition;

/**
 * Machine-level narration: exactly what the shell decided, including the next phase. Observers
 * narrate; they never influence (§8). TurnObserver adaptation is built on top of this seam.
 */
public interface AgentObserver {

  /** One event applied: the fact and the whole transition — next phase, commits, effects. */
  void applied(AgentEvent event, Transition transition);

  /** A stale or duplicate completion, discarded before anything was written (§3.4). */
  void ignored(AgentEvent event);

  /** A renderer threw; the observation is discarded and the scope stays idle (§3.7). */
  void renderFailed(Object observation, RuntimeException error);

  /** Accepts everything, tells no one. */
  static AgentObserver noop() {
    return new AgentObserver() {
      @Override
      public void applied(AgentEvent event, Transition transition) {}

      @Override
      public void ignored(AgentEvent event) {}

      @Override
      public void renderFailed(Object observation, RuntimeException error) {}
    };
  }
}
