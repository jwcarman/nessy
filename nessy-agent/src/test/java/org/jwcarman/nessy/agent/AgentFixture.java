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
package org.jwcarman.nessy.agent;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.LatentSink;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingMemory;
import org.jwcarman.nessy.agent.support.RecordingObserver;
import org.jwcarman.nessy.agent.support.ScriptedModelExecutor;
import org.jwcarman.nessy.agent.support.ScriptedToolExecutor;
import org.jwcarman.nessy.api.message.TextBlock;

/** One fully-wired agent on a pump; the fixture is the test's vocabulary. */
final class AgentFixture {
  final PumpedExecutor pump = new PumpedExecutor();
  final LatentSink sink = new LatentSink();
  final RecordingMemory memory = new RecordingMemory();
  final RecordingObserver observer = new RecordingObserver();
  final ScriptedModelExecutor model = new ScriptedModelExecutor(pump, sink, memory);
  final ScriptedToolExecutor tools = new ScriptedToolExecutor(pump, sink);
  final Deque<String> backlogQueue = new ArrayDeque<>();
  final Backlog<String> backlog =
      new Backlog<>() {
        @Override
        public void add(String observation) {
          backlogQueue.add(observation);
        }

        @Override
        public Optional<String> poll() {
          return Optional.ofNullable(backlogQueue.poll());
        }
      };
  final AgentStateStore store;
  final DefaultAgent<String> agent;

  AgentFixture(AgentStateStore store, boolean drainOnIdle, Duration staleThreshold, Clock clock) {
    this.store = store;
    this.agent =
        (DefaultAgent<String>)
            DefaultAgent.create(
                new AgentWiring<>(
                    memory,
                    store,
                    backlog,
                    text -> List.of(new TextBlock(text)),
                    model,
                    tools,
                    observer,
                    drainOnIdle,
                    staleThreshold,
                    clock),
                sink);
  }

  AgentFixture(AgentStateStore store, boolean drainOnIdle) {
    this(store, drainOnIdle, Duration.ofMinutes(5), Clock.systemUTC());
  }

  AgentFixture() {
    this(new InMemoryAgentStateStore(), false);
  }
}
