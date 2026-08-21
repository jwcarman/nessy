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
package org.jwcarman.nessy.agent.support;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.Effect;
import org.jwcarman.nessy.agent.Transition;
import org.jwcarman.nessy.agent.spi.AgentObserver;

/** Collects every {@code reFired} call in order; every other callback is a silent no-op. */
public final class RecordingAgentObserver implements AgentObserver {

  private final List<List<Effect>> reFiredCalls = new ArrayList<>();

  @Override
  public void applied(AgentEvent event, Transition transition) {
    // silent no-op: only reFired is recorded
  }

  @Override
  public void ignored(AgentEvent event) {
    // silent no-op: only reFired is recorded
  }

  @Override
  public void renderFailed(Object observation, RuntimeException error) {
    // silent no-op: only reFired is recorded
  }

  @Override
  public void applyFailed(AgentEvent event, RuntimeException error) {
    // silent no-op: only reFired is recorded
  }

  @Override
  public void reFired(List<Effect> effects) {
    reFiredCalls.add(List.copyOf(effects));
  }

  @Override
  public void observationRequeued(Object observation) {
    // silent no-op: only reFired is recorded
  }

  public List<List<Effect>> reFiredCalls() {
    return List.copyOf(reFiredCalls);
  }
}
