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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

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
    var substrate = new InMemorySubstrate();
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
            .substrate(substrate)
            .turnObserver(throwing)
            .build();

    host.post("scope-1", "hello");
    pump.pumpUntilQuiet();

    var scopeOneState =
        new SubstrateAgentStateStore(
            substrate, "scope-1", Clock.systemUTC(), TestMappers.plainlyPinned());
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
   * untouched); substrate independence is read off two distinct {@link Substrate}s installed
   * through the builder's one storage seam, {@link
   * org.jwcarman.nessy.agent.host.Nessy.AutonomousBuilder#substrate} — {@code storeFactory} is gone
   * (spec §12), so the seam that gives an honest window onto otherwise-opaque substrate state is
   * now {@code substrate} itself — and this pins that a second host's first delivery to a scope
   * starts from a fresh, unadvanced version, not one built on top of the first host's saves.
   */
  @Test
  void twoBuildCallsFromOneBuilderWithDistinctStoresDoNotLeakHistory() {
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.TextChunk("reply one")),
                List.of(new ModelEvent.TextChunk("reply two"))));

    var builder = Nessy.autonomous().provider(provider).settings(TestSettings.settings());

    var substrateOne = new InMemorySubstrate();
    var pumpOne = new PumpedExecutor();
    var hostOne = builder.executor(pumpOne).substrate(substrateOne).build();
    hostOne.post("shared-scope", "message one");
    pumpOne.pumpUntilQuiet();

    var substrateTwo = new InMemorySubstrate();
    var pumpTwo = new PumpedExecutor();
    var hostTwo = builder.executor(pumpTwo).substrate(substrateTwo).build();
    hostTwo.post("shared-scope", "message two");
    pumpTwo.pumpUntilQuiet();

    List<ModelRequest> requests = provider.requests();
    assertThat(requests).hasSize(2);
    List<Message> secondHostMessages = requests.get(1).context().messages();
    assertThat(secondHostMessages)
        .isNotEmpty()
        .noneMatch(m -> m.content().contains(new TextBlock("message one")));

    var stateOne =
        new SubstrateAgentStateStore(
            substrateOne, "shared-scope", Clock.systemUTC(), TestMappers.plainlyPinned());
    var stateTwo =
        new SubstrateAgentStateStore(
            substrateTwo, "shared-scope", Clock.systemUTC(), TestMappers.plainlyPinned());
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
   * The substrate reform's whole point (spec §12): durability lives in the {@link Substrate}, not
   * in any object graph the builder happens to wire together. Neither {@code memoryFactory} nor
   * {@code storeFactory} is overridden here — the host uses its default {@code id -> new
   * SubstrateMemory(substrate, id)} recipe over the one substrate this test supplies — so the only
   * thing tying the two deliveries together is the shared {@link Substrate}. Proof is read back
   * through a SECOND, independently-constructed {@code SubstrateMemory} over that same substrate: a
   * fresh recipe instance, never touched by the host, still recalls both turns.
   */
  @Test
  void twoDeliveriesToTheSameAgentShareOneSubstrateProvenByASecondMemoryBinding() {
    var pump = new PumpedExecutor();
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.TextChunk("first reply")),
                List.of(new ModelEvent.TextChunk("second reply"))));
    var substrate = new InMemorySubstrate();

    var host =
        Nessy.autonomous()
            .provider(provider)
            .settings(TestSettings.settings())
            .executor(pump)
            .substrate(substrate)
            .build();

    host.post("scope-1", "first message");
    pump.pumpUntilQuiet();
    host.post("scope-1", "second message");
    pump.pumpUntilQuiet();

    // a fresh recipe instance the host never held a reference to — the substrate, not the object
    // graph, is what makes this see both turns
    var secondBindingOverTheSameStore =
        new SubstrateMemory(substrate, "scope-1", TestMappers.plainlyPinned());
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
   * from one builder: durability lives in the {@link Substrate} itself, so a second host — built
   * later, from its own builder, knowing nothing about the first — still inherits the first host's
   * turn the moment it's pointed at the same substrate. The proof rides the model request host B's
   * own provider recorded: its context carries host A's turn.
   */
  @Test
  void aSecondHostBuiltOverTheSameSubstrateInheritsTheFirstHostsTurn() {
    var substrate = new InMemorySubstrate();

    var pumpA = new PumpedExecutor();
    var providerA =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.TextChunk("reply one"))));
    var hostA =
        Nessy.autonomous()
            .provider(providerA)
            .settings(TestSettings.settings())
            .executor(pumpA)
            .substrate(substrate)
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
            .substrate(substrate)
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

  /**
   * Task 3 (bytes-and-codecs): one mapper in, pinned copy throughout (spec §7). {@code
   * .objectMapper(ObjectMapper)} feeds {@code build()}'s one pinned copy into every recipe that
   * binds JSON — the tool-call binder included.
   */
  @Nested
  class ObjectMapperThreading {

    /** A user shape whose wire form ({@code "$12.34"}) only a registered module can produce. */
    record Money(long cents) {}

    record ChargeInput(Money amount) {}

    static final class MoneySerializer extends JsonSerializer<Money> {
      @Override
      public void serialize(Money value, JsonGenerator gen, SerializerProvider serializers)
          throws IOException {
        gen.writeString("$%d.%02d".formatted(value.cents() / 100, value.cents() % 100));
      }
    }

    static final class MoneyDeserializer extends JsonDeserializer<Money> {
      @Override
      public Money deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String digits = p.getValueAsString().replace("$", "").replace(".", "");
        return new Money(Long.parseLong(digits));
      }
    }

    static final class ChargeTool implements Tool<ChargeInput> {
      @Override
      public String name() {
        return "charge";
      }

      @Override
      public String description() {
        return "charges an amount";
      }

      @Override
      public Class<ChargeInput> inputType() {
        return ChargeInput.class;
      }

      @Override
      public Awaited<ToolResult> execute(ChargeInput input, ToolContext context) {
        return Awaited.ready(ToolResult.ok(String.valueOf(input.amount().cents())));
      }
    }

    record EchoInput(String value) {}

    static final class EchoTool implements Tool<EchoInput> {
      @Override
      public String name() {
        return "echo";
      }

      @Override
      public String description() {
        return "echoes";
      }

      @Override
      public Class<EchoInput> inputType() {
        return EchoInput.class;
      }

      @Override
      public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
        return Awaited.ready(ToolResult.ok("echo: " + input.value()));
      }
    }

    @Test
    void aUserRegisteredModuleFlowsThroughToToolInputBinding() {
      var module = new SimpleModule();
      module.addSerializer(Money.class, new MoneySerializer());
      module.addDeserializer(Money.class, new MoneyDeserializer());
      var userMapper = new ObjectMapper().registerModule(module);

      var pump = new PumpedExecutor();
      var call =
          new ToolCall(
              "c1", "charge", JsonNodeFactory.instance.objectNode().put("amount", "$12.34"));
      var provider =
          new ScriptedModelProvider(
              List.of(
                  List.of(new ModelEvent.ToolUseEmitted(call, null)),
                  List.of(new ModelEvent.TextChunk("charged"))));
      var observer = new RecordingTurnObserver();

      var host =
          Nessy.autonomous()
              .provider(provider)
              .settings(TestSettings.settings())
              .executor(pump)
              .objectMapper(userMapper)
              .tools(new ChargeTool())
              .turnObserver(observer)
              .build();

      host.post("scope-1", "charge please");
      pump.pumpUntilQuiet();

      var completed =
          observer.events().stream()
              .filter(TurnEvent.ToolCallCompleted.class::isInstance)
              .map(TurnEvent.ToolCallCompleted.class::cast)
              .findFirst();
      assertThat(completed).isPresent();
      assertThat(completed.get().result().content()).isEqualTo("1234");
    }

    /**
     * The pin holds even when the caller's own mapper is configured for something else entirely:
     * {@code build()} pins lower-camel property naming on its copy (spec §7), so the substrate's
     * stored JSON stays camelCase — {@code ToolResultBlock}'s golden fields, {@code toolUseId} and
     * {@code isError} — regardless of what the caller's mapper prefers.
     */
    @Test
    void theBuilderPinsTheStoredFormatEvenWhenTheUsersMapperPrefersSnakeCase() {
      var substrate = new InMemorySubstrate();
      var snakeCaseMapper =
          new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
      var pump = new PumpedExecutor();
      var call =
          new ToolCall("c1", "echo", JsonNodeFactory.instance.objectNode().put("value", "hi"));
      var provider =
          new ScriptedModelProvider(
              List.of(
                  List.of(new ModelEvent.ToolUseEmitted(call, null)),
                  List.of(new ModelEvent.TextChunk("done"))));

      var host =
          Nessy.autonomous()
              .provider(provider)
              .settings(TestSettings.settings())
              .executor(pump)
              .substrate(substrate)
              .objectMapper(snakeCaseMapper)
              .tools(new EchoTool())
              .build();

      host.post("scope-1", "hi");
      pump.pumpUntilQuiet();

      List<Substrate.Entry> entries = substrate.entries("memory", "scope-1", 1);
      assertThat(entries).isNotEmpty();
      String allPayloads =
          entries.stream()
              .map(e -> new String(e.payload(), StandardCharsets.UTF_8))
              .collect(Collectors.joining("\n"));
      assertThat(allPayloads).contains("\"toolUseId\"").contains("\"isError\"");
      assertThat(allPayloads).doesNotContain("tool_use_id").doesNotContain("is_error");
    }

    @Test
    void aNullObjectMapperIsRejectedByItsSetter() {
      var builder = Nessy.autonomous();
      assertThatThrownBy(() -> builder.objectMapper(null)).isInstanceOf(NullPointerException.class);
    }
  }
}
