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
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.memory.StoredMemory;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.store.StoredAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.store.InMemoryScopedStore;
import org.jwcarman.nessy.spi.store.ScopedStore;

class AutonomousHostTest {

  @Test
  void aPlainTurnRunsToIdleThroughTheHost() {
    var pump = new PumpedExecutor();
    var provider =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.TextChunk("hello back"))));
    ConcurrentMap<String, Memory> captured = new ConcurrentHashMap<>();

    var host =
        Nessy.autonomous()
            .provider(provider)
            .settings(TestSettings.settings())
            .executor(pump)
            .memoryFactory(id -> captured.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .build();

    host.post("scope-1", "hello");
    pump.pumpUntilQuiet();

    Memory memory = captured.get("scope-1");
    assertThat(memory).isNotNull();
    List<Message> messages = memory.recall().messages();
    assertThat(messages)
        .isNotEmpty()
        .anyMatch(m -> m.content().contains(new TextBlock("hello")))
        .anyMatch(m -> m.content().contains(new TextBlock("hello back")));
  }

  @Test
  void aDefaultBuiltHostNarratesExactlyOneAssistantSaidAndOneTurnEndedForACompletedTurn() {
    var pump = new PumpedExecutor();
    var provider =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.TextChunk("hello back"))));
    var observer = new RecordingTurnObserver();

    var host =
        Nessy.autonomous()
            .provider(provider)
            .settings(TestSettings.settings())
            .executor(pump)
            .turnObserver(observer)
            .build();

    host.post("scope-1", "hello");
    pump.pumpUntilQuiet();

    List<TurnEvent> events = observer.events();
    assertThat(events).isNotEmpty();

    List<TurnEvent> assistantSaid =
        events.stream().filter(TurnEvent.AssistantSaid.class::isInstance).toList();
    assertThat(assistantSaid).isNotEmpty().hasSize(1);

    List<TurnEvent> turnEnded =
        events.stream().filter(TurnEvent.TurnEnded.class::isInstance).toList();
    assertThat(turnEnded).isNotEmpty().hasSize(1);
  }

  /**
   * A caller-supplied {@code agentObserver} replaces the default {@link
   * org.jwcarman.nessy.agent.narrate.TurnNarrationAdapter} wiring wholesale (Nessy.java's own
   * setter promise): {@code AssistantSaid}/{@code TurnEnded} do not narrate on the turn observer
   * unless the supplied observer narrates them itself. {@code events} still isn't empty — the model
   * and tool executors narrate deltas and tool events directly, independent of {@code
   * agentObserver} — so the {@code noneMatch} below can't pass vacuously (S5841).
   */
  @Test
  void aSuppliedAgentObserverReplacesTheDefaultNarrationWiringWholesale() {
    var pump = new PumpedExecutor();
    var provider =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.TextChunk("hello back"))));
    var observer = new RecordingTurnObserver();

    var host =
        Nessy.autonomous()
            .provider(provider)
            .settings(TestSettings.settings())
            .executor(pump)
            .turnObserver(observer)
            .agentObserver(AgentObserver.noop())
            .build();

    host.post("scope-1", "hello");
    pump.pumpUntilQuiet();

    List<TurnEvent> events = observer.events();
    assertThat(events).isNotEmpty();
    assertThat(events)
        .noneMatch(TurnEvent.AssistantSaid.class::isInstance)
        .noneMatch(TurnEvent.TurnEnded.class::isInstance);
  }

  /**
   * The fix for a real stall (found reviewing narration): before this, {@link
   * org.jwcarman.nessy.agent.narrate.TurnNarrationAdapter#applied} let a throwing observer escape,
   * which aborted {@code DefaultAgent.applyOnce} before it dispatched the transition's effects —
   * the scope's saved phase and its dispatched effects fell out of step, and nothing but the
   * staleness recovery arm, minutes later, would have re-fired them. A throwing {@code
   * onAssistantSaid} must not stop the model-call effect from dispatching, so the scope still
   * settles on {@link Phase.Idle} in the same pump.
   */
  @Test
  void aThrowingTurnObserverDoesNotStallTheScopesEffectsOrCompletion() {
    var pump = new PumpedExecutor();
    var provider =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.TextChunk("hello back"))));
    var kernel = new InMemoryScopedStore();
    TurnObserver throwing =
        event -> {
          if (event instanceof TurnEvent.AssistantSaid) {
            throw new RuntimeException("narration boom");
          }
        };

    var host =
        Nessy.autonomous()
            .provider(provider)
            .settings(TestSettings.settings())
            .executor(pump)
            .store(kernel)
            .turnObserver(throwing)
            .build();

    host.post("scope-1", "hello");
    pump.pumpUntilQuiet();

    var scopeOneState = new StoredAgentStateStore(kernel, "scope-1", Clock.systemUTC());
    assertThat(scopeOneState.load().phase()).isEqualTo(new Phase.Idle());
  }

  @Test
  void twoScopesDoNotShareMemoryOrState() {
    var pump = new PumpedExecutor();
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.TextChunk("hi a")),
                List.of(new ModelEvent.TextChunk("hi b"))));
    ConcurrentMap<String, Memory> captured = new ConcurrentHashMap<>();

    var host =
        Nessy.autonomous()
            .provider(provider)
            .settings(TestSettings.settings())
            .executor(pump)
            .memoryFactory(id -> captured.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .build();

    host.post("a", "hello from a");
    pump.pumpUntilQuiet();
    host.post("b", "hello from b");
    pump.pumpUntilQuiet();

    List<Message> aMessages = captured.get("a").recall().messages();
    List<Message> bMessages = captured.get("b").recall().messages();

    assertThat(aMessages)
        .isNotEmpty()
        .allMatch(m -> !m.content().contains(new TextBlock("hello from b")));
    assertThat(bMessages)
        .isNotEmpty()
        .allMatch(m -> !m.content().contains(new TextBlock("hello from a")));
  }

  /**
   * F3: two {@code build()} calls from the SAME builder don't leak history between hosts. Memory
   * independence is read straight off the model requests (the default {@code memoryFactory} is left
   * untouched); store independence is read off two distinct {@link ScopedStore}s installed through
   * the builder's one storage seam, {@link
   * org.jwcarman.nessy.agent.host.Nessy.AutonomousBuilder#store} — {@code storeFactory} is gone
   * (spec §12), so the seam that gives an honest window onto otherwise-opaque store state is now
   * {@code store} itself — and this pins that a second host's first delivery to a scope starts from
   * a fresh, unadvanced version, not one built on top of the first host's saves.
   */
  @Test
  void twoBuildCallsFromOneBuilderWithDistinctStoresDoNotLeakHistory() {
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.TextChunk("reply one")),
                List.of(new ModelEvent.TextChunk("reply two"))));

    var builder = Nessy.autonomous().provider(provider).settings(TestSettings.settings());

    var kernelOne = new InMemoryScopedStore();
    var pumpOne = new PumpedExecutor();
    var hostOne = builder.executor(pumpOne).store(kernelOne).build();
    hostOne.post("shared-scope", "message one");
    pumpOne.pumpUntilQuiet();

    var kernelTwo = new InMemoryScopedStore();
    var pumpTwo = new PumpedExecutor();
    var hostTwo = builder.executor(pumpTwo).store(kernelTwo).build();
    hostTwo.post("shared-scope", "message two");
    pumpTwo.pumpUntilQuiet();

    List<ModelRequest> requests = provider.requests();
    assertThat(requests).hasSize(2);
    List<Message> secondHostMessages = requests.get(1).context().messages();
    assertThat(secondHostMessages)
        .isNotEmpty()
        .noneMatch(m -> m.content().contains(new TextBlock("message one")));

    var stateOne = new StoredAgentStateStore(kernelOne, "shared-scope", Clock.systemUTC());
    var stateTwo = new StoredAgentStateStore(kernelTwo, "shared-scope", Clock.systemUTC());
    long versionAfterHostOnesTurn = stateOne.load().version();
    long versionAfterHostTwosTurn = stateTwo.load().version();
    assertThat(versionAfterHostTwosTurn)
        .as(
            "host two's scope should run the same number of transitions as host one's, from a"
                + " fresh version, not one already advanced by host one's saves")
        .isEqualTo(versionAfterHostOnesTurn);
  }

  @Test
  void backlogCapacityRejectsLessThanOneAtBuildTimeConfiguration() {
    var builder = Nessy.autonomous();

    assertThatThrownBy(() -> builder.backlogCapacity(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("backlogCapacity must be at least 1");
  }

  /**
   * There is no per-id wiring cache any more (§10.11): {@code agentFor(id)} binds a fresh handle
   * from the shared substrate on every call. This is the reform's whole point in one test — two
   * deliveries to the same scope, each through a brand-new binding, still see each other's history
   * because the substrate underneath persists it, not the (deleted) cache.
   */
  @Test
  void aSecondPostToTheSameScopeSeesTheFirstPostsHistoryEvenThoughEveryDeliveryBindsAFreshHandle() {
    var pump = new PumpedExecutor();
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.TextChunk("first reply")),
                List.of(new ModelEvent.TextChunk("second reply"))));
    ConcurrentMap<String, Memory> captured = new ConcurrentHashMap<>();

    var host =
        Nessy.autonomous()
            .provider(provider)
            .settings(TestSettings.settings())
            .executor(pump)
            .memoryFactory(id -> captured.computeIfAbsent(id, ignored -> new VerbatimMemory()))
            .build();

    host.post("scope-1", "first message");
    pump.pumpUntilQuiet();
    host.post("scope-1", "second message");
    pump.pumpUntilQuiet();

    List<Message> messages = captured.get("scope-1").recall().messages();
    assertThat(messages)
        .isNotEmpty()
        .anyMatch(m -> m.content().contains(new TextBlock("first message")))
        .anyMatch(m -> m.content().contains(new TextBlock("first reply")))
        .anyMatch(m -> m.content().contains(new TextBlock("second message")))
        .anyMatch(m -> m.content().contains(new TextBlock("second reply")));
  }

  /**
   * The storage-kernel reform's whole point (spec §12): durability lives in the {@link
   * ScopedStore}, not in any object graph the builder happens to wire together. Neither {@code
   * memoryFactory} nor {@code storeFactory} is overridden here — the host uses its default {@code
   * id -> new StoredMemory(store, id)} recipe over the one {@link #store} this test supplies — so
   * the only thing tying the two deliveries together is the shared {@link ScopedStore}. Proof is
   * read back through a SECOND, independently-constructed {@code StoredMemory} over that same
   * store: a fresh recipe instance, never touched by the host, still recalls both turns.
   */
  @Test
  void twoDeliveriesToTheSameAgentShareOneScopedStoreProvenByASecondMemoryBinding() {
    var pump = new PumpedExecutor();
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.TextChunk("first reply")),
                List.of(new ModelEvent.TextChunk("second reply"))));
    var kernel = new InMemoryScopedStore();

    var host =
        Nessy.autonomous()
            .provider(provider)
            .settings(TestSettings.settings())
            .executor(pump)
            .store(kernel)
            .build();

    host.post("scope-1", "first message");
    pump.pumpUntilQuiet();
    host.post("scope-1", "second message");
    pump.pumpUntilQuiet();

    // a fresh recipe instance the host never held a reference to — the store, not the object
    // graph, is what makes this see both turns
    var secondBindingOverTheSameStore = new StoredMemory(kernel, "scope-1");
    List<Message> messages = secondBindingOverTheSameStore.recall().messages();
    assertThat(messages)
        .isNotEmpty()
        .anyMatch(m -> m.content().contains(new TextBlock("first message")))
        .anyMatch(m -> m.content().contains(new TextBlock("first reply")))
        .anyMatch(m -> m.content().contains(new TextBlock("second message")))
        .anyMatch(m -> m.content().contains(new TextBlock("second reply")));
  }

  /**
   * The branch's headline claim, proven with two entirely separate hosts rather than two builds
   * from one builder: durability lives in the {@link ScopedStore} itself, so a second host — built
   * later, from its own builder, knowing nothing about the first — still inherits the first host's
   * turn the moment it's pointed at the same kernel. The proof rides the model request host B's own
   * provider recorded: its context carries host A's turn.
   */
  @Test
  void aSecondHostBuiltOverTheSameKernelInheritsTheFirstHostsTurn() {
    var kernel = new InMemoryScopedStore();

    var pumpA = new PumpedExecutor();
    var providerA =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.TextChunk("reply one"))));
    var hostA =
        Nessy.autonomous()
            .provider(providerA)
            .settings(TestSettings.settings())
            .executor(pumpA)
            .store(kernel)
            .build();
    hostA.post("shared-scope", "message one");
    pumpA.pumpUntilQuiet();

    var pumpB = new PumpedExecutor();
    var providerB =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.TextChunk("reply two"))));
    var hostB =
        Nessy.autonomous()
            .provider(providerB)
            .settings(TestSettings.settings())
            .executor(pumpB)
            .store(kernel)
            .build();
    hostB.post("shared-scope", "message two");
    pumpB.pumpUntilQuiet();

    List<ModelRequest> requestsToHostB = providerB.requests();
    assertThat(requestsToHostB).hasSize(1);
    List<Message> secondTurnContext = requestsToHostB.get(0).context().messages();
    assertThat(secondTurnContext)
        .isNotEmpty()
        .anyMatch(m -> m.content().contains(new TextBlock("message one")))
        .anyMatch(m -> m.content().contains(new TextBlock("reply one")));
  }
}
