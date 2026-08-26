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

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingMemory;
import org.jwcarman.nessy.agent.support.RecordingObserver;
import org.jwcarman.nessy.agent.support.ScriptedModelExecutor;
import org.jwcarman.nessy.agent.support.ScriptedToolExecutor;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/** One fully-wired agent on a pump; the fixture is the test's vocabulary. */
final class AgentFixture {
  final PumpedExecutor pump = new PumpedExecutor();
  final RecordingMemory memory = new RecordingMemory();
  final RecordingObserver observer = new RecordingObserver();
  final ScriptedModelExecutor model = new ScriptedModelExecutor(pump, memory);
  final ScriptedToolExecutor tools = new ScriptedToolExecutor(pump);
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

  AgentFixture(AgentStateStore store, boolean drainOnIdle, StalenessPolicy stalenessPolicy) {
    this.store = store;
    // this fixture's own harness owns its own life-support (spec §4) over a private, throwaway
    // substrate — decoupled from whatever substrate the caller-supplied `store` above lives on.
    Substrate lifeSupportSubstrate = new InMemorySubstrate();
    var mapper = TestMappers.plainlyPinned();
    var fixtureType = AgentType.of("fixture");
    var approvalClient = TestApprovalClients.client(Kinds.approval(fixtureType), mapper);
    var toolClient = TestToolClients.client(Kinds.tool(fixtureType), mapper);
    Harness<String> harness =
        Harness.of(
            fixtureType,
            "test_provider",
            "test-model",
            text -> List.of(new TextBlock(text)),
            List.of(observer),
            TurnObserver.noop(),
            drainOnIdle,
            stalenessPolicy,
            rawId -> memory,
            rawId -> store,
            rawId -> backlog,
            (mem, obs) -> model,
            (id, obs) -> tools,
            lifeSupportSubstrate,
            mapper,
            approvalClient,
            toolClient,
            new ConcurrentHashMap<>(),
            ObservationRegistry.NOOP,
            new ConcurrentHashMap<>());
    HarnessTeardown.track(harness);
    this.agent = new DefaultAgent<>(harness, harness.binding(AgentId.of("fixture-scope")));
  }

  AgentFixture(AgentStateStore store, boolean drainOnIdle) {
    this(store, drainOnIdle, StalenessPolicy.never());
  }

  AgentFixture() {
    this(
        new SubstrateAgentStateStore(
            new InMemorySubstrate(),
            "fixture-scope",
            Clock.systemUTC(),
            TestMappers.plainlyPinned()),
        false);
  }
}
