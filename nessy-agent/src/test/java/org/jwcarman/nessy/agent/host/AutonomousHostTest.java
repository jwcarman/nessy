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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;

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
    assertThat(messages).isNotEmpty();
    assertThat(messages)
        .anyMatch(m -> m.content().contains(new TextBlock("hello")))
        .anyMatch(m -> m.content().contains(new TextBlock("hello back")));
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

    assertThat(aMessages).isNotEmpty();
    assertThat(aMessages).allMatch(m -> !m.content().contains(new TextBlock("hello from b")));
    assertThat(bMessages).isNotEmpty();
    assertThat(bMessages).allMatch(m -> !m.content().contains(new TextBlock("hello from a")));
  }

  /**
   * F3: the default memoryFactory/storeFactory substrates are built inside {@code build()}, not
   * once in field initializers — so two {@code build()} calls from the SAME builder each get their
   * own fresh default substrates and don't leak history between hosts. Memory independence is read
   * straight off the model requests (defaults left untouched); store independence is read off
   * captured stores installed through {@code storeFactory} — the cheapest honest window onto
   * otherwise-opaque default substrate state — and pins that a second host's first delivery to a
   * scope starts from a fresh, unadvanced version, not one built on top of the first host's saves.
   */
  @Test
  void twoBuildCallsFromOneBuilderDoNotShareDefaultSubstrateState() {
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.TextChunk("reply one")),
                List.of(new ModelEvent.TextChunk("reply two"))));

    var builder = Nessy.autonomous().provider(provider).settings(TestSettings.settings());

    ConcurrentMap<String, AgentStateStore> storesOne = new ConcurrentHashMap<>();
    var pumpOne = new PumpedExecutor();
    var hostOne =
        builder
            .executor(pumpOne)
            .storeFactory(
                id -> storesOne.computeIfAbsent(id, ignored -> new InMemoryAgentStateStore()))
            .build();
    hostOne.post("shared-scope", "message one");
    pumpOne.pumpUntilQuiet();

    ConcurrentMap<String, AgentStateStore> storesTwo = new ConcurrentHashMap<>();
    var pumpTwo = new PumpedExecutor();
    var hostTwo =
        builder
            .executor(pumpTwo)
            .storeFactory(
                id -> storesTwo.computeIfAbsent(id, ignored -> new InMemoryAgentStateStore()))
            .build();
    hostTwo.post("shared-scope", "message two");
    pumpTwo.pumpUntilQuiet();

    List<ModelRequest> requests = provider.requests();
    assertThat(requests).hasSize(2);
    List<Message> secondHostMessages = requests.get(1).context().messages();
    assertThat(secondHostMessages).isNotEmpty();
    assertThat(secondHostMessages)
        .noneMatch(m -> m.content().contains(new TextBlock("message one")));

    long versionAfterHostOnesTurn = storesOne.get("shared-scope").load().version();
    long versionAfterHostTwosTurn = storesTwo.get("shared-scope").load().version();
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
    assertThat(messages).isNotEmpty();
    assertThat(messages)
        .anyMatch(m -> m.content().contains(new TextBlock("first message")))
        .anyMatch(m -> m.content().contains(new TextBlock("first reply")))
        .anyMatch(m -> m.content().contains(new TextBlock("second message")))
        .anyMatch(m -> m.content().contains(new TextBlock("second reply")));
  }
}
