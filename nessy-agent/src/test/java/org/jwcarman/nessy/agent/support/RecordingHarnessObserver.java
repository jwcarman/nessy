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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Effect;
import org.jwcarman.nessy.agent.Transition;
import org.jwcarman.nessy.agent.spi.HarnessObserver;

/**
 * The one recording subscriber the fact-stream fixtures share: collects the applied folds, the
 * ignored ones, and every {@code reFired} call, each in arrival order.
 *
 * <p>Thread-safe by construction — a fold published from a delivery worker's own thread lands here
 * while the test thread reads — so the lists are {@link CopyOnWriteArrayList}s and the accessors
 * hand back snapshots.
 */
public final class RecordingHarnessObserver implements HarnessObserver {

  /** One applied fold, flattened to what a test asserts on. */
  public record Applied(AgentId id, AgentEvent event, Transition transition) {}

  /** One ignored fold: the scope it was meant for, and the event that changed nothing. */
  public record Ignored(AgentId id, AgentEvent event) {}

  private final List<Applied> applied = new CopyOnWriteArrayList<>();
  private final List<Ignored> ignored = new CopyOnWriteArrayList<>();
  private final List<List<Effect>> reFiredCalls = new CopyOnWriteArrayList<>();

  @Override
  public void applied(AgentId id, AgentEvent event, Transition transition) {
    applied.add(new Applied(id, event, transition));
  }

  @Override
  public void ignored(AgentId id, AgentEvent event) {
    ignored.add(new Ignored(id, event));
  }

  @Override
  public void renderFailed(AgentId id, Object observation, RuntimeException error) {
    // not recorded: no fixture asserts on render failures through the stream
  }

  @Override
  public void applyFailed(AgentId id, AgentEvent event, RuntimeException error) {
    // not recorded: no fixture asserts on apply failures through the stream
  }

  @Override
  public void reFired(AgentId id, List<Effect> effects) {
    reFiredCalls.add(List.copyOf(effects));
  }

  @Override
  public void observationRequeued(AgentId id, Object observation) {
    // not recorded: no fixture asserts on requeues through the stream
  }

  public List<Applied> applied() {
    return List.copyOf(applied);
  }

  public List<Ignored> ignored() {
    return List.copyOf(ignored);
  }

  public List<List<Effect>> reFiredCalls() {
    return List.copyOf(reFiredCalls);
  }
}
