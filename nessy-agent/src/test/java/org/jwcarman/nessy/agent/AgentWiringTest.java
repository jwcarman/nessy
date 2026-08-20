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

  @Test
  void everyCollaboratorIsRequired() {
    var store = new InMemoryAgentStateStore();
    ObservationRenderer<String> renderer = text -> List.of();
    ModelCallExecutor model = () -> {};
    ToolCallExecutor tools = call -> {};
    assertThatThrownBy(
            () ->
                new AgentWiring<>(
                    null,
                    store,
                    BACKLOG,
                    renderer,
                    model,
                    tools,
                    AgentObserver.noop(),
                    false,
                    Duration.ofMinutes(5),
                    Clock.systemUTC()))
        .isInstanceOf(NullPointerException.class);
  }
}
