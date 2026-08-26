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
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Effect;
import org.jwcarman.nessy.agent.Transition;
import org.jwcarman.nessy.agent.spi.HarnessObserver;

/** Writes down everything the shell says. */
public final class RecordingObserver implements HarnessObserver {

  public record Applied(AgentEvent event, Transition transition) {}

  private final List<Applied> applied = new ArrayList<>();
  private final List<AgentEvent> ignored = new ArrayList<>();
  private final List<Object> renderFailures = new ArrayList<>();
  private final List<AgentEvent> applyFailures = new ArrayList<>();
  private final List<List<Effect>> reFires = new ArrayList<>();
  private final List<Object> requeued = new ArrayList<>();

  @Override
  public void applied(AgentId id, AgentEvent event, Transition transition) {
    applied.add(new Applied(event, transition));
  }

  @Override
  public void ignored(AgentId id, AgentEvent event) {
    ignored.add(event);
  }

  @Override
  public void renderFailed(AgentId id, Object observation, RuntimeException error) {
    renderFailures.add(observation);
  }

  @Override
  public void applyFailed(AgentId id, AgentEvent event, RuntimeException error) {
    applyFailures.add(event);
  }

  @Override
  public void reFired(AgentId id, List<Effect> effects) {
    reFires.add(effects);
  }

  @Override
  public void observationRequeued(AgentId id, Object observation) {
    requeued.add(observation);
  }

  public List<Applied> applied() {
    return List.copyOf(applied);
  }

  public List<AgentEvent> ignored() {
    return List.copyOf(ignored);
  }

  public List<Object> renderFailures() {
    return List.copyOf(renderFailures);
  }

  public List<AgentEvent> applyFailures() {
    return List.copyOf(applyFailures);
  }

  public List<List<Effect>> reFires() {
    return List.copyOf(reFires);
  }

  public List<Object> requeued() {
    return List.copyOf(requeued);
  }
}
