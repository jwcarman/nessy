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

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.backlog.SubstrateBacklog;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.TestCodecs;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

class HarnessTest {

  private static final Memory MEMORY =
      new Memory() {
        @Override
        public void remember(Remembrance remembrance) {
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

  private static final AgentStateStore STORE =
      new SubstrateAgentStateStore(
          new InMemorySubstrate(),
          "harness-fixture",
          Clock.systemUTC(),
          TestMappers.plainlyPinned());
  private static final ObservationRenderer<String> RENDERER = text -> List.of();
  private static final ModelCallExecutor MODEL = sink -> {};
  private static final ToolCallExecutor TOOLS = (call, responseId, sink) -> {};
  private static final AgentObserver OBSERVER = AgentObserver.noop();
  private static final StalenessPolicy STALENESS_POLICY = StalenessPolicy.never();
  private static final AgentType TYPE = AgentType.of("test");

  /**
   * Every {@link Harness} now owns its own life-support (spec §4) — a delivery worker, daemon-
   * threaded — so this helper synthesizes its own throwaway, private {@link Substrate} for that
   * purpose, entirely decoupled from whatever storage {@code memoryFactory}/{@code storeFactory}
   * actually exercise in a given test.
   */
  private static Harness<String> harness(
      AgentType type,
      ObservationRenderer<String> renderer,
      AgentObserver observer,
      StalenessPolicy stalenessPolicy,
      Function<String, Memory> memoryFactory,
      Function<String, AgentStateStore> storeFactory,
      Function<String, Backlog<String>> backlogFactory,
      BiFunction<Memory, TurnObserver, ModelCallExecutor> modelExecutorFactory,
      BiFunction<AgentId, TurnObserver, ToolCallExecutor> toolExecutorFactory) {
    return harness(
        type,
        renderer,
        observer,
        TurnObserver.noop(),
        stalenessPolicy,
        memoryFactory,
        storeFactory,
        backlogFactory,
        modelExecutorFactory,
        toolExecutorFactory);
  }

  /**
   * As the nine-arg overload, but naming {@code turnObserver} rather than defaulting it to {@link
   * TurnObserver#noop()} — fix round 1, MINOR-6: {@link Guards#harnessRequiresATurnObserver()}
   * needs a null to pass through.
   */
  private static Harness<String> harness(
      AgentType type,
      ObservationRenderer<String> renderer,
      AgentObserver observer,
      TurnObserver turnObserver,
      StalenessPolicy stalenessPolicy,
      Function<String, Memory> memoryFactory,
      Function<String, AgentStateStore> storeFactory,
      Function<String, Backlog<String>> backlogFactory,
      BiFunction<Memory, TurnObserver, ModelCallExecutor> modelExecutorFactory,
      BiFunction<AgentId, TurnObserver, ToolCallExecutor> toolExecutorFactory) {
    Substrate lifeSupportSubstrate = new InMemorySubstrate();
    var mapper = TestMappers.plainlyPinned();
    String outboxKind = Kinds.outbox(type);
    SubstrateComputations approvalBackend =
        new SubstrateComputations(lifeSupportSubstrate, mapper, Kinds.approval(type), outboxKind);
    SubstrateComputations executionBackend =
        new SubstrateComputations(
            lifeSupportSubstrate, mapper, Kinds.computation(type), outboxKind);
    // Preserves harnessRequiresAnObserver()'s null-through behavior: a literal null observer
    // stays a literal null factory (Harness.of's own requireNonNull rejects it), rather than
    // silently becoming a non-null factory that always hands back null.
    Function<TurnObserver, AgentObserver> agentObserverFactory =
        observer == null ? null : perIdTurnObserver -> observer;
    return Harness.of(
        type,
        renderer,
        agentObserverFactory,
        turnObserver,
        false,
        stalenessPolicy,
        memoryFactory,
        storeFactory,
        backlogFactory,
        modelExecutorFactory,
        toolExecutorFactory,
        lifeSupportSubstrate,
        mapper,
        approvalBackend,
        executionBackend,
        new ConcurrentHashMap<>());
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
      var id = AgentId.of("a");
      assertThatThrownBy(() -> new Binding<>(id, null, STORE, BACKLOG))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void bindingRequiresAStore() {
      var id = AgentId.of("a");
      assertThatThrownBy(() -> new Binding<>(id, MEMORY, null, BACKLOG))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void bindingRequiresABacklog() {
      var id = AgentId.of("a");
      assertThatThrownBy(() -> new Binding<>(id, MEMORY, STORE, null))
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
                      (mem, obs) -> MODEL,
                      (id, obs) -> TOOLS))
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
                      (mem, obs) -> MODEL,
                      (id, obs) -> TOOLS))
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
                      (mem, obs) -> MODEL,
                      (id, obs) -> TOOLS))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void harnessRequiresATurnObserver() {
      assertThatThrownBy(
              () ->
                  harness(
                      TYPE,
                      RENDERER,
                      OBSERVER,
                      null,
                      STALENESS_POLICY,
                      id -> MEMORY,
                      id -> STORE,
                      id -> BACKLOG,
                      (mem, obs) -> MODEL,
                      (id, obs) -> TOOLS))
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
                      (mem, obs) -> MODEL,
                      (id, obs) -> TOOLS))
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
                      (mem, obs) -> MODEL,
                      (id, obs) -> TOOLS))
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
                      (mem, obs) -> MODEL,
                      (id, obs) -> TOOLS))
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
                      (mem, obs) -> MODEL,
                      (id, obs) -> TOOLS))
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
                      (id, obs) -> TOOLS))
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
                      (mem, obs) -> MODEL,
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
              (mem, obs) -> null,
              (id, obs) -> TOOLS);
      Binding<String> binding = harness.binding(AgentId.of("a"));

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
              (mem, obs) -> MODEL,
              (id, obs) -> null);
      Binding<String> binding = harness.binding(AgentId.of("a"));

      assertThatThrownBy(() -> new DefaultAgent<>(harness, binding))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class BindingStamping {

    @Test
    void twoDifferentIdsGetDistinctHandlesThatDoNotLeakIntoEachOther() {
      var substrate = new InMemorySubstrate();
      Harness<String> harness =
          harness(
              TYPE,
              RENDERER,
              OBSERVER,
              STALENESS_POLICY,
              id -> new SubstrateMemory(substrate, id, TestMappers.plainlyPinned()),
              id ->
                  new SubstrateAgentStateStore(
                      substrate, id, Clock.systemUTC(), TestMappers.plainlyPinned()),
              id -> new SubstrateBacklog<>(substrate, id, 16, TestCodecs.utf8String()),
              (mem, obs) -> MODEL,
              (id, obs) -> TOOLS);

      var bindingA = harness.binding(AgentId.of("scope-a"));
      var bindingB = harness.binding(AgentId.of("scope-b"));

      bindingA.memory().remember(new Remembrance.UserMessage("key-a", Message.user(List.of())));
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
      var substrate = new InMemorySubstrate();
      Harness<String> harness =
          harness(
              TYPE,
              RENDERER,
              OBSERVER,
              STALENESS_POLICY,
              id -> new SubstrateMemory(substrate, id, TestMappers.plainlyPinned()),
              id ->
                  new SubstrateAgentStateStore(
                      substrate, id, Clock.systemUTC(), TestMappers.plainlyPinned()),
              id -> new SubstrateBacklog<>(substrate, id, 16, TestCodecs.utf8String()),
              (mem, obs) -> MODEL,
              (id, obs) -> TOOLS);

      var id = AgentId.of("shared-scope");
      var firstBind = harness.binding(id);
      firstBind.memory().remember(new Remembrance.UserMessage("key-b", Message.user(List.of())));
      firstBind.backlog().add("hello");

      var secondBind = harness.binding(id);

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
              (mem, obs) -> {
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
              (id, obs) -> TOOLS);

      harness.modelExecutor(harness.binding(AgentId.of("a")));
      harness.modelExecutor(harness.binding(AgentId.of("b")));

      assertThat(produced).hasSize(2);
      assertThat(produced.get(0)).isNotSameAs(produced.get(1));
      assertThat(receivedByFactory).isNotEmpty().allMatch(o -> o == registry);
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
              (mem, obs) -> MODEL,
              (id, obs) -> {
                receivedByFactory.add(registry);
                ToolCallExecutor fresh =
                    new ToolCallExecutor() {
                      @Override
                      public void executeTool(
                          ToolCall call, ModelResponseId responseId, Sink sink) {
                        // fixture only: this test cares about factory freshness, not tool output
                      }
                    };
                produced.add(fresh);
                return fresh;
              });

      harness.toolExecutor(harness.binding(AgentId.of("a")));
      harness.toolExecutor(harness.binding(AgentId.of("b")));

      assertThat(produced).hasSize(2);
      assertThat(produced.get(0)).isNotSameAs(produced.get(1));
      assertThat(receivedByFactory).isNotEmpty().allMatch(o -> o == registry);
    }
  }

  /**
   * Harness-first spec §4: {@code bind(AgentId)} is now the application door, returning {@link
   * Agent}.
   */
  @Nested
  class BindReturnsAnAgent {

    @Test
    void bindReturnsAnAgentThatDrainsIntoTheSameSubstrateBindingExposes() {
      var substrate = new InMemorySubstrate();
      Harness<String> harness =
          harness(
              TYPE,
              text -> List.of(new TextBlock(text)),
              OBSERVER,
              STALENESS_POLICY,
              id -> new SubstrateMemory(substrate, id, TestMappers.plainlyPinned()),
              id ->
                  new SubstrateAgentStateStore(
                      substrate, id, Clock.systemUTC(), TestMappers.plainlyPinned()),
              id -> new SubstrateBacklog<>(substrate, id, 16, TestCodecs.utf8String()),
              (mem, obs) -> MODEL,
              (id, obs) -> TOOLS);

      Agent<String> agent = harness.bind(AgentId.of("scope-a"));
      agent.tell("hello");

      // tell() drove the scope from Idle: the observation was drained and applied, not left
      // sitting in the backlog — proven by reading the SAME id's backlog back through the raw
      // binding door, over the same shared substrate.
      var binding = harness.binding(AgentId.of("scope-a"));
      assertThat(binding.backlog().poll()).isEmpty();
      assertThat(binding.memory().recall().messages()).isNotEmpty();
    }
  }
}
