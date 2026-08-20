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
import org.jwcarman.nessy.agent.Transition;
import org.jwcarman.nessy.agent.spi.AgentObserver;

/** Writes down everything the shell says. */
public final class RecordingObserver implements AgentObserver {

  public record Applied(AgentEvent event, Transition transition) {}

  private final List<Applied> applied = new ArrayList<>();
  private final List<AgentEvent> ignored = new ArrayList<>();
  private final List<Object> renderFailures = new ArrayList<>();

  @Override
  public void applied(AgentEvent event, Transition transition) {
    applied.add(new Applied(event, transition));
  }

  @Override
  public void ignored(AgentEvent event) {
    ignored.add(event);
  }

  @Override
  public void renderFailed(Object observation, RuntimeException error) {
    renderFailures.add(observation);
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
}
