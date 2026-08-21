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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.backlog.InMemoryBacklogSubstrate;
import org.jwcarman.nessy.agent.memory.InMemoryMemorySubstrate;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.store.InMemoryStateSubstrate;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.Memory;

class HarnessTest {

  private static final Memory MEMORY =
      new Memory() {
        @Override
        public void remember(Message message) {
          // fixture only: this memory never needs to recall what it was told
        }

        @Override
        public Context recall() {
          return Context.empty();
        }
      };

  private static final Backlog<String> BACKLOG =
      new Backlog<>() {
        @Override
        public void add(String observation) {
          // fixture only: this backlog never needs to hold what it was given
        }

        @Override
        public Optional<String> poll() {
          return Optional.empty();
        }
      };

  private static final AgentStateStore STORE = new InMemoryAgentStateStore();
  private static final ObservationRenderer<String> RENDERER = text -> List.of();
  private static final ModelCallExecutor MODEL = sink -> {};
  private static final ToolCallExecutor TOOLS = (call, sink) -> {};
  private static final AgentObserver OBSERVER = AgentObserver.noop();
  private static final StalenessPolicy STALENESS_POLICY = StalenessPolicy.never();
  private static final AgentType TYPE = AgentType.of("test");

  private static Harness<String> harness(
      AgentType type,
      ObservationRenderer<String> renderer,
      AgentObserver observer,
      StalenessPolicy stalenessPolicy,
      Function<String, Memory> memoryFactory,
      Function<String, AgentStateStore> storeFactory,
      Function<String, Backlog<String>> backlogFactory,
      Function<Binding<String>, ModelCallExecutor> modelExecutorFactory,
      Function<Binding<String>, ToolCallExecutor> toolExecutorFactory) {
    return Harness.of(
        type,
        renderer,
        observer,
        false,
        stalenessPolicy,
        memoryFactory,
        storeFactory,
        backlogFactory,
        modelExecutorFactory,
        toolExecutorFactory);
  }

  @Nested
  class Guards {

    @Test
    void bindingRequiresAnId() {
      assertThatThrownBy(() -> new Binding<>(null, MEMORY, STORE, BACKLOG))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void bindingRequiresMemory() {
      assertThatThrownBy(() -> new Binding<>(AgentId.of("a"), null, STORE, BACKLOG))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void bindingRequiresAStore() {
      assertThatThrownBy(() -> new Binding<>(AgentId.of("a"), MEMORY, null, BACKLOG))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void bindingRequiresABacklog() {
      assertThatThrownBy(() -> new Binding<>(AgentId.of("a"), MEMORY, STORE, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void harnessRequiresAType() {
      assertThatThrownBy(
              () ->
                  harness(
                      null,
                      RENDERER,
                      OBSERVER,
                      STALENESS_POLICY,
                      id -> MEMORY,
                      id -> STORE,
                      id -> BACKLOG,
                      binding -> MODEL,
                      binding -> TOOLS))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void harnessRequiresARenderer() {
      assertThatThrownBy(
              () ->
                  harness(
                      TYPE,
                      null,
                      OBSERVER,
                      STALENESS_POLICY,
                      id -> MEMORY,
                      id -> STORE,
                      id -> BACKLOG,
                      binding -> MODEL,
                      binding -> TOOLS))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void harnessRequiresAnObserver() {
      assertThatThrownBy(
              () ->
                  harness(
                      TYPE,
                      RENDERER,
                      null,
                      STALENESS_POLICY,
                      id -> MEMORY,
                      id -> STORE,
                      id -> BACKLOG,
                      binding -> MODEL,
                      binding -> TOOLS))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void harnessRequiresAStalenessPolicy() {
      assertThatThrownBy(
              () ->
                  harness(
                      TYPE,
                      RENDERER,
                      OBSERVER,
                      null,
                      id -> MEMORY,
                      id -> STORE,
                      id -> BACKLOG,
                      binding -> MODEL,
                      binding -> TOOLS))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void harnessRequiresAMemoryFactory() {
      assertThatThrownBy(
              () ->
                  harness(
                      TYPE,
                      RENDERER,
                      OBSERVER,
                      STALENESS_POLICY,
                      null,
                      id -> STORE,
                      id -> BACKLOG,
                      binding -> MODEL,
                      binding -> TOOLS))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void harnessRequiresAStoreFactory() {
      assertThatThrownBy(
              () ->
                  harness(
                      TYPE,
                      RENDERER,
                      OBSERVER,
                      STALENESS_POLICY,
                      id -> MEMORY,
                      null,
                      id -> BACKLOG,
                      binding -> MODEL,
                      binding -> TOOLS))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void harnessRequiresABacklogFactory() {
      assertThatThrownBy(
              () ->
                  harness(
                      TYPE,
                      RENDERER,
                      OBSERVER,
                      STALENESS_POLICY,
                      id -> MEMORY,
                      id -> STORE,
                      null,
                      binding -> MODEL,
                      binding -> TOOLS))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void harnessRequiresAModelExecutorFactory() {
      assertThatThrownBy(
              () ->
                  harness(
                      TYPE,
                      RENDERER,
                      OBSERVER,
                      STALENESS_POLICY,
                      id -> MEMORY,
                      id -> STORE,
                      id -> BACKLOG,
                      null,
                      binding -> TOOLS))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void harnessRequiresAToolExecutorFactory() {
      assertThatThrownBy(
              () ->
                  harness(
                      TYPE,
                      RENDERER,
                      OBSERVER,
                      STALENESS_POLICY,
                      id -> MEMORY,
                      id -> STORE,
                      id -> BACKLOG,
                      binding -> MODEL,
                      null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructingADefaultAgentWithAModelExecutorFactoryThatReturnsNullFailsLoudly() {
      Harness<String> harness =
          harness(
              TYPE,
              RENDERER,
              OBSERVER,
              STALENESS_POLICY,
              id -> MEMORY,
              id -> STORE,
              id -> BACKLOG,
              b -> null,
              b -> TOOLS);
      Binding<String> binding = harness.bind(AgentId.of("a"));

      assertThatThrownBy(() -> new DefaultAgent<>(harness, binding))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructingADefaultAgentWithAToolExecutorFactoryThatReturnsNullFailsLoudly() {
      Harness<String> harness =
          harness(
              TYPE,
              RENDERER,
              OBSERVER,
              STALENESS_POLICY,
              id -> MEMORY,
              id -> STORE,
              id -> BACKLOG,
              b -> MODEL,
              b -> null);
      Binding<String> binding = harness.bind(AgentId.of("a"));

      assertThatThrownBy(() -> new DefaultAgent<>(harness, binding))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class BindingStamping {

    @Test
    void twoDifferentIdsGetDistinctHandlesThatDoNotLeakIntoEachOther() {
      var memorySubstrate = new InMemoryMemorySubstrate();
      var stateSubstrate = new InMemoryStateSubstrate();
      var backlogSubstrate = new InMemoryBacklogSubstrate(16);
      Harness<String> harness =
          harness(
              TYPE,
              RENDERER,
              OBSERVER,
              STALENESS_POLICY,
              memorySubstrate::forScope,
              stateSubstrate::forScope,
              backlogSubstrate::forScope,
              b -> MODEL,
              b -> TOOLS);

      var bindingA = harness.bind(AgentId.of("scope-a"));
      var bindingB = harness.bind(AgentId.of("scope-b"));

      bindingA.memory().remember(Message.user(List.of()));
      bindingA
          .store()
          .save(new State(new Phase.AwaitingModel(), bindingA.store().load().version()));
      bindingA.backlog().add("only for a");

      assertThat(bindingA.id()).isNotEqualTo(bindingB.id());
      assertThat(bindingA.memory().recall().messages()).isNotEmpty();
      assertThat(bindingB.memory().recall().messages()).isEmpty();
      assertThat(bindingA.store().load().phase()).isEqualTo(new Phase.AwaitingModel());
      assertThat(bindingB.store().load().phase()).isEqualTo(new Phase.Idle());
      assertThat(bindingB.backlog().poll()).isEmpty();
    }

    @Test
    void bindingTheSameIdTwiceSeesTheSameSubstrate() {
      var memorySubstrate = new InMemoryMemorySubstrate();
      var stateSubstrate = new InMemoryStateSubstrate();
      var backlogSubstrate = new InMemoryBacklogSubstrate(16);
      Harness<String> harness =
          harness(
              TYPE,
              RENDERER,
              OBSERVER,
              STALENESS_POLICY,
              memorySubstrate::forScope,
              stateSubstrate::forScope,
              backlogSubstrate::forScope,
              b -> MODEL,
              b -> TOOLS);

      var id = AgentId.of("shared-scope");
      var firstBind = harness.bind(id);
      firstBind.memory().remember(Message.user(List.of()));
      firstBind.backlog().add("hello");

      var secondBind = harness.bind(id);

      assertThat(secondBind.memory().recall().messages()).isNotEmpty();
      assertThat(secondBind.backlog().poll()).contains("hello");
    }

    @Test
    void
        theModelExecutorFactoryIsInvokedFreshOnEveryBindAndReceivesTheSameCapturedRegistryEachTime() {
      Object registry = new Object();
      List<Object> receivedByFactory = new ArrayList<>();
      List<ModelCallExecutor> produced = new ArrayList<>();
      Harness<String> harness =
          harness(
              TYPE,
              RENDERER,
              OBSERVER,
              STALENESS_POLICY,
              id -> MEMORY,
              id -> STORE,
              id -> BACKLOG,
              b -> {
                receivedByFactory.add(registry);
                ModelCallExecutor fresh =
                    new ModelCallExecutor() {
                      @Override
                      public void callModel(Sink sink) {
                        // fixture only: this test cares about factory freshness, not model output
                      }
                    };
                produced.add(fresh);
                return fresh;
              },
              b -> TOOLS);

      harness.modelExecutor(harness.bind(AgentId.of("a")));
      harness.modelExecutor(harness.bind(AgentId.of("b")));

      assertThat(produced).hasSize(2);
      assertThat(produced.get(0)).isNotSameAs(produced.get(1));
      assertThat(receivedByFactory).isNotEmpty();
      assertThat(receivedByFactory).allMatch(o -> o == registry);
    }

    @Test
    void
        theToolExecutorFactoryIsInvokedFreshOnEveryBindAndReceivesTheSameCapturedRegistryEachTime() {
      Object registry = new Object();
      List<Object> receivedByFactory = new ArrayList<>();
      List<ToolCallExecutor> produced = new ArrayList<>();
      Harness<String> harness =
          harness(
              TYPE,
              RENDERER,
              OBSERVER,
              STALENESS_POLICY,
              id -> MEMORY,
              id -> STORE,
              id -> BACKLOG,
              b -> MODEL,
              b -> {
                receivedByFactory.add(registry);
                ToolCallExecutor fresh =
                    new ToolCallExecutor() {
                      @Override
                      public void executeTool(ToolCall call, Sink sink) {
                        // fixture only: this test cares about factory freshness, not tool output
                      }
                    };
                produced.add(fresh);
                return fresh;
              });

      harness.toolExecutor(harness.bind(AgentId.of("a")));
      harness.toolExecutor(harness.bind(AgentId.of("b")));

      assertThat(produced).hasSize(2);
      assertThat(produced.get(0)).isNotSameAs(produced.get(1));
      assertThat(receivedByFactory).isNotEmpty();
      assertThat(receivedByFactory).allMatch(o -> o == registry);
    }
  }
}
