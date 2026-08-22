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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.StoredAgentStateStore;
import org.jwcarman.nessy.agent.support.RecordingAgentObserver;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The redrive door's own short-circuits: an Idle scope narrates nothing, and a stalled model call
 * is never re-fired from here (spec §4.3 amendment).
 */
class DefaultAgentRedispatchTest {

  private static final class CountingModelCallExecutor implements ModelCallExecutor {
    private int invocations;

    @Override
    public void callModel(Sink sink) {
      invocations++;
    }
  }

  private static final class CountingToolCallExecutor implements ToolCallExecutor {
    private int invocations;

    @Override
    public void executeTool(ToolCall call, Sink sink) {
      invocations++;
    }
  }

  @Test
  void anIdleScopeNarratesNothingAndDispatchesNothingOnRedispatch() {
    var store =
        new StoredAgentStateStore(
            new InMemorySubstrate(), "agent", Clock.systemUTC(), TestMappers.plainlyPinned());
    var model = new CountingModelCallExecutor();
    var tools = new CountingToolCallExecutor();
    var observer = new RecordingAgentObserver();
    var agent =
        TestAgents.<String>wired(
            new VerbatimMemory(),
            store,
            new NoopBacklog(),
            text -> List.of(new TextBlock(text)),
            model,
            tools,
            observer,
            false,
            StalenessPolicy.never());

    agent.redispatch();

    assertThat(observer.reFiredCalls()).isEmpty();
    assertThat(model.invocations).isZero();
    assertThat(tools.invocations).isZero();
  }

  @Test
  void anAwaitingModelScopeDispatchesNothingBecauseCallModelIsFilteredOut() {
    var store =
        new StoredAgentStateStore(
            new InMemorySubstrate(), "agent", Clock.systemUTC(), TestMappers.plainlyPinned());
    store.save(new State(new Phase.AwaitingModel(), store.load().version()));
    var model = new CountingModelCallExecutor();
    var tools = new CountingToolCallExecutor();
    var observer = new RecordingAgentObserver();
    var agent =
        TestAgents.<String>wired(
            new VerbatimMemory(),
            store,
            new NoopBacklog(),
            text -> List.of(new TextBlock(text)),
            model,
            tools,
            observer,
            false,
            StalenessPolicy.never());

    agent.redispatch();

    assertThat(observer.reFiredCalls()).containsExactly(List.of());
    assertThat(model.invocations).isZero();
    assertThat(tools.invocations).isZero();
  }

  private static final class NoopBacklog implements Backlog<String> {
    @Override
    public void add(String observation) {
      // fixture only: this backlog never needs to hold what it was given
    }

    @Override
    public Optional<String> poll() {
      return Optional.empty();
    }
  }
}
