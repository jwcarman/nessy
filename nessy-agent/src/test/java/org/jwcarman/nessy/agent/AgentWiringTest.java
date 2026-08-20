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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.Memory;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;

class AgentWiringTest {

  private static final Memory MEMORY =
      new Memory() {
        @Override
        public void remember(Message message) {}

        @Override
        public Context recall() {
          return Context.empty();
        }
      };

  private static final Backlog<String> BACKLOG =
      new Backlog<>() {
        @Override
        public void add(String observation) {}

        @Override
        public Optional<String> poll() {
          return Optional.empty();
        }
      };

  private static final AgentStateStore STORE = new InMemoryAgentStateStore();
  private static final ObservationRenderer<String> RENDERER = text -> List.of();
  private static final ModelCallExecutor MODEL = () -> {};
  private static final ToolCallExecutor TOOLS = call -> {};
  private static final AgentObserver OBSERVER = AgentObserver.noop();
  private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
  private static final Clock CLOCK = Clock.systemUTC();

  private static AgentWiring<String> wiring(
      Memory memory,
      AgentStateStore store,
      Backlog<String> backlog,
      ObservationRenderer<String> renderer,
      ModelCallExecutor model,
      ToolCallExecutor tools,
      AgentObserver observer,
      Duration staleThreshold,
      Clock clock) {
    return new AgentWiring<>(
        memory, store, backlog, renderer, model, tools, observer, false, staleThreshold, clock);
  }

  @Test
  void memoryIsRequired() {
    assertThatThrownBy(
            () ->
                wiring(
                    null, STORE, BACKLOG, RENDERER, MODEL, TOOLS, OBSERVER, STALE_THRESHOLD, CLOCK))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void storeIsRequired() {
    assertThatThrownBy(
            () ->
                wiring(
                    MEMORY,
                    null,
                    BACKLOG,
                    RENDERER,
                    MODEL,
                    TOOLS,
                    OBSERVER,
                    STALE_THRESHOLD,
                    CLOCK))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void backlogIsRequired() {
    assertThatThrownBy(
            () ->
                wiring(
                    MEMORY, STORE, null, RENDERER, MODEL, TOOLS, OBSERVER, STALE_THRESHOLD, CLOCK))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rendererIsRequired() {
    assertThatThrownBy(
            () ->
                wiring(
                    MEMORY, STORE, BACKLOG, null, MODEL, TOOLS, OBSERVER, STALE_THRESHOLD, CLOCK))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void modelIsRequired() {
    assertThatThrownBy(
            () ->
                wiring(
                    MEMORY,
                    STORE,
                    BACKLOG,
                    RENDERER,
                    null,
                    TOOLS,
                    OBSERVER,
                    STALE_THRESHOLD,
                    CLOCK))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void toolsIsRequired() {
    assertThatThrownBy(
            () ->
                wiring(
                    MEMORY,
                    STORE,
                    BACKLOG,
                    RENDERER,
                    MODEL,
                    null,
                    OBSERVER,
                    STALE_THRESHOLD,
                    CLOCK))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void observerIsRequired() {
    assertThatThrownBy(
            () ->
                wiring(
                    MEMORY, STORE, BACKLOG, RENDERER, MODEL, TOOLS, null, STALE_THRESHOLD, CLOCK))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void staleThresholdIsRequired() {
    assertThatThrownBy(
            () -> wiring(MEMORY, STORE, BACKLOG, RENDERER, MODEL, TOOLS, OBSERVER, null, CLOCK))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void clockIsRequired() {
    assertThatThrownBy(
            () ->
                wiring(
                    MEMORY,
                    STORE,
                    BACKLOG,
                    RENDERER,
                    MODEL,
                    TOOLS,
                    OBSERVER,
                    STALE_THRESHOLD,
                    null))
        .isInstanceOf(NullPointerException.class);
  }
}
