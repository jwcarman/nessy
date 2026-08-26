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
package org.jwcarman.nessy.spring.boot;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentTransition;
import org.jwcarman.nessy.agent.Effect;
import org.jwcarman.nessy.agent.spi.HarnessObserver;

/**
 * A {@link HarnessObserver} that only counts — the application-supplied observer these tests prove
 * the starter subscribes alongside its own. Hand-written rather than mocked: the design of record's
 * no-mocking-library promise, and a counter is less code than a stub would be anyway.
 */
final class CountingObserver implements HarnessObserver {

  private final AtomicInteger applied = new AtomicInteger();

  int applied() {
    return applied.get();
  }

  @Override
  public void applied(AgentId id, AgentEvent event, AgentTransition transition) {
    applied.incrementAndGet();
  }

  @Override
  public void ignored(AgentId id, AgentEvent event) {
    // counted nowhere: these tests only ever assert on applied facts
  }

  @Override
  public void renderFailed(AgentId id, Object observation, RuntimeException error) {
    // counted nowhere: these tests only ever assert on applied facts
  }

  @Override
  public void applyFailed(AgentId id, AgentEvent event, RuntimeException error) {
    // counted nowhere: these tests only ever assert on applied facts
  }

  @Override
  public void reFired(AgentId id, List<Effect> effects) {
    // counted nowhere: these tests only ever assert on applied facts
  }

  @Override
  public void observationRequeued(AgentId id, Object observation) {
    // counted nowhere: these tests only ever assert on applied facts
  }
}
