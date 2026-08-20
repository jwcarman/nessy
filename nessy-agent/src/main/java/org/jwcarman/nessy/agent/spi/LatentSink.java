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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.agent.AgentEvent;

/**
 * The construction-order answer (§4): executors hold their Sink from birth, but the sink's target
 * exists only after the agent is built. A factory constructs executors around a LatentSink and
 * binds it once, immediately after constructing the agent. Delivering before the bind is a wiring
 * bug and fails loudly.
 */
public final class LatentSink implements Sink {

  private final AtomicReference<Sink> target = new AtomicReference<>();

  public void bind(Sink sink) {
    Objects.requireNonNull(sink, "sink must not be null");
    if (!target.compareAndSet(null, sink)) {
      throw new IllegalStateException("this sink is already bound");
    }
  }

  @Override
  public void deliver(AgentEvent event) {
    Sink bound = target.get();
    if (bound == null) {
      throw new IllegalStateException("sink delivered to before being bound");
    }
    bound.deliver(event);
  }
}
