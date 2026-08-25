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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * {@link Nessy#harness(HarnessCustomizer)}/{@link Nessy#harness(Class, HarnessCustomizer)} — the
 * one door (harness-first spec §2) — replace what this file used to cover through the deleted
 * long-running host shim: {@code tell} through {@code bind(id)} in place of {@code post}, the
 * harness kept rather than closed, and the customizer form in place of a builder's {@code build()}.
 */
class NessyHarnessDoorTest {

  /**
   * Fix round 1 M5: every harness this file builds owns a live delivery-worker heartbeat
   * (harness-first spec §4); each construction below is tracked via {@link HarnessTeardown#track},
   * and this reclaims all of them once per test method.
   */
  @AfterEach
  void tearDown() {
    HarnessTeardown.shutdownAllTracked();
  }

  @Test
  void aPlainTurnRunsToIdleThroughTheHarness() {
    var pump = new PumpedExecutor();
    var provider = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hello back"))));
    ConcurrentMap<String, Memory> captured = new ConcurrentHashMap<>();

    var harness =
        Nessy.harness(
            h ->
                h.model(provider)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .executor(pump)
                    .memoryFactory(
                        id -> captured.computeIfAbsent(id, ignored -> new VerbatimMemory())));
    HarnessTeardown.track(harness);

    harness.bind(AgentId.of("scope-1")).tell("hello");
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
  void aDefaultBuiltHarnessNarratesExactlyOneAssistantSaidAndOneTurnEndedForACompletedTurn() {
    var pump = new PumpedExecutor();
    var provider = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hello back"))));
    var observer = new RecordingTurnObserver();

    var harness =
        Nessy.harness(
            h ->
                h.model(provider)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .executor(pump)
                    .turnObserver(observer));
    HarnessTeardown.track(harness);

    harness.bind(AgentId.of("scope-1")).tell("hello");
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
    var provider = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hello back"))));
    var observer = new RecordingTurnObserver();

    var harness =
        Nessy.harness(
            h ->
                h.model(provider)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .executor(pump)
                    .turnObserver(observer)
                    .agentObserver(AgentObserver.noop()));
    HarnessTeardown.track(harness);

    harness.bind(AgentId.of("scope-1")).tell("hello");
    pump.pumpUntilQuiet();

    List<TurnEvent> events = observer.events();
    assertThat(events)
        .isNotEmpty()
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
    var provider = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hello back"))));
    var substrate = new InMemorySubstrate();
    TurnObserver throwing =
        event -> {
          if (event instanceof TurnEvent.AssistantSaid) {
            throw new RuntimeException("narration boom");
          }
        };

    var harness =
        Nessy.harness(
            h ->
                h.model(provider)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .executor(pump)
                    .substrate(substrate)
                    .turnObserver(throwing));
    HarnessTeardown.track(harness);

    harness.bind(AgentId.of("scope-1")).tell("hello");
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
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.TextChunk("hi a")),
                List.of(new ModelEvent.TextChunk("hi b"))));
    ConcurrentMap<String, Memory> captured = new ConcurrentHashMap<>();

    var harness =
        Nessy.harness(
            h ->
                h.model(provider)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .executor(pump)
                    .memoryFactory(
                        id -> captured.computeIfAbsent(id, ignored -> new VerbatimMemory())));
    HarnessTeardown.track(harness);

    harness.bind(AgentId.of("a")).tell("hello from a");
    pump.pumpUntilQuiet();
    harness.bind(AgentId.of("b")).tell("hello from b");
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
   * F3: two harnesses built from the SAME base customization don't leak history between them. There
   * is no shared, half-configured config object in user hands to reuse (harness-first spec §2) —
   * each {@link Nessy#harness(HarnessCustomizer)} call gets its own fresh {@code HarnessConfig} —
   * but a base {@link HarnessCustomizer} composed into two separate calls proves the same point:
   * memory independence is read straight off the model requests (the default {@code memoryFactory}
   * is left untouched); substrate independence is read off two distinct {@link Substrate}s
   * installed through the config's one storage seam, {@link HarnessConfig#substrate} — and this
   * pins that a second harness's first delivery to a scope starts from a fresh, unadvanced version,
   * not one built on top of the first harness's saves.
   */
  @Test
  void twoHarnessesFromTheSameBaseCustomizationWithDistinctStoresDoNotLeakHistory() {
    var provider =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.TextChunk("reply one")),
                List.of(new ModelEvent.TextChunk("reply two"))));

    HarnessCustomizer<String> base =
        h ->
            h.model(provider)
                .systemPrompt(TestSettings.SYSTEM_PROMPT)
                .settings(TestSettings.settings());

    var substrateOne = new InMemorySubstrate();
    var pumpOne = new PumpedExecutor();
    var harnessOne =
        Nessy.harness(
            h -> {
              base.customize(h);
              h.executor(pumpOne).substrate(substrateOne);
            });
    HarnessTeardown.track(harnessOne);
    harnessOne.bind(AgentId.of("shared-scope")).tell("message one");
    pumpOne.pumpUntilQuiet();

    var substrateTwo = new InMemorySubstrate();
    var pumpTwo = new PumpedExecutor();
    var harnessTwo =
        Nessy.harness(
            h -> {
              base.customize(h);
              h.executor(pumpTwo).substrate(substrateTwo);
            });
    HarnessTeardown.track(harnessTwo);
    harnessTwo.bind(AgentId.of("shared-scope")).tell("message two");
    pumpTwo.pumpUntilQuiet();

    List<ModelRequest> requests = provider.requests();
    assertThat(requests).hasSize(2);
    List<Message> secondHarnessMessages = requests.get(1).context().messages();
    assertThat(secondHarnessMessages)
        .isNotEmpty()
        .noneMatch(m -> m.content().contains(new TextBlock("message one")));

    var stateOne =
        new SubstrateAgentStateStore(
            substrateOne, "shared-scope", Clock.systemUTC(), TestMappers.plainlyPinned());
    var stateTwo =
        new SubstrateAgentStateStore(
            substrateTwo, "shared-scope", Clock.systemUTC(), TestMappers.plainlyPinned());
    long versionAfterHarnessOnesTurn = stateOne.load().version();
    long versionAfterHarnessTwosTurn = stateTwo.load().version();
    assertThat(versionAfterHarnessTwosTurn)
        .as(
            "harness two's scope should run the same number of transitions as harness one's,"
                + " from a fresh version, not one already advanced by harness one's saves")
        .isEqualTo(versionAfterHarnessOnesTurn);
  }

  @Test
  void backlogCapacityRejectsLessThanOneInsideTheCustomizer() {
    assertThatThrownBy(() -> Nessy.harness(h -> h.backlogCapacity(0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("backlogCapacity must be at least 1");
  }

  /**
   * The builder minimum (spec §3, amended §7): {@code .settings(ModelSettings)} is now optional —
   * omitted, {@code finish()} falls back to {@link ModelSettings#defaults()} — the teaching
   * missing-{@code .model(Model)} and missing-{@code .systemPrompt(String)} messages, the §1
   * snippet's bare minimum, and {@code .type(String)}'s "agent" default.
   */
  @Nested
  class BuilderMinimum {

    /**
     * Spec §7: {@code .settings(...)} is the OPTIONAL tuning bag. Omitted entirely, {@code
     * finish()} falls back to {@link ModelSettings#defaults()} — the captured request carries the
     * default max-tokens budget and the harness's own {@code .systemPrompt(...)}.
     */
    @Test
    void settingsOmittedFallsBackToTheDefaultMaxTokensAndTheHarnessesSystemPrompt() {
      var pump = new PumpedExecutor();
      var model = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("reply"))));

      var harness =
          Nessy.harness(
              h -> h.model(model).systemPrompt(TestSettings.SYSTEM_PROMPT).executor(pump));
      HarnessTeardown.track(harness);

      harness.bind(AgentId.of("scope-1")).tell("hello");
      pump.pumpUntilQuiet();

      ModelRequest request = model.requests().getFirst();
      assertThat(request.systemPrompt()).isEqualTo(TestSettings.SYSTEM_PROMPT);
      assertThat(request.maxTokens()).isEqualTo(ModelSettings.DEFAULT_MAX_TOKENS);
    }

    /**
     * Spec §3: {@code .model(Model)} is the harness's one required dependency — the teaching
     * message names the setter to call, not just that something is null.
     */
    @Test
    void aMissingModelIsRejectedWithATeachingMessage() {
      assertThatThrownBy(() -> Nessy.harness(h -> h.systemPrompt(TestSettings.SYSTEM_PROMPT)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining(".model(Model)");
    }

    /**
     * Spec §3, §7: {@code .systemPrompt(String)} no longer has a settings fallback — it is required
     * on its own, and the teaching message names it.
     */
    @Test
    void aMissingSystemPromptIsRejectedWithATeachingMessage() {
      var model = new ScriptedModel(List.of());

      assertThatThrownBy(() -> Nessy.harness(h -> h.model(model)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining(".systemPrompt(String)");
    }

    /**
     * The §1 snippet's own claim: a harness built with ONLY {@code .model(...)}, {@code
     * .systemPrompt(...)}, {@code .tools(...)} — no settings, no substrate, no type — accepts
     * {@code bind(id).tell(...)} and completes a turn.
     */
    @Test
    void theSpecSnippetsBareMinimumAcceptsAnObservationAndCompletesATurn() {
      var pump = new PumpedExecutor();
      var model = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hello back"))));

      var harness =
          Nessy.harness(
              h -> h.model(model).systemPrompt(TestSettings.SYSTEM_PROMPT).tools().executor(pump));
      HarnessTeardown.track(harness);

      harness.bind(AgentId.of("scope-1")).tell("restart prod-eu");
      pump.pumpUntilQuiet();

      List<Message> messages = model.requests().getFirst().context().messages();
      assertThat(messages)
          .isNotEmpty()
          .anyMatch(m -> m.content().contains(new TextBlock("restart prod-eu")));
    }

    /**
     * Fix round 1 I1c: {@code .type(String)} defaults to {@code "agent"} — observed through a
     * scripted turn's own {@code CallAddress}: a required-approval tool call parks an approval
     * computation addressed by the harness's agent type.
     */
    @Test
    void theDefaultAgentTypeIsAgent() {
      var pump = new PumpedExecutor();
      var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
      var provider = new ScriptedModel(List.of(List.of(new ModelEvent.ToolUseEmitted(call, null))));

      var harness =
          Nessy.harness(
              h ->
                  h.model(provider)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pump)
                      .grants(ToolGrant.grant(new GatedTool(), Approvers.defer())));
      HarnessTeardown.track(harness);

      harness.bind(AgentId.of("scope-1")).tell("please restart");
      pump.pumpUntilQuiet();

      assertThat(harness.approvals().request(AgentId.of("scope-1"), "c1").agentType())
          .isEqualTo("agent");
    }

    record NoInput() {}

    static final class GatedTool implements Tool<NoInput> {
      @Override
      public String name() {
        return "restart";
      }

      @Override
      public String description() {
        return "restarts something; requires human approval";
      }

      @Override
      public Class<NoInput> inputType() {
        return NoInput.class;
      }

      @Override
      public CompletionPolicy requiredCompletion() {
        return CompletionPolicy.DURABLE;
      }

      @Override
      public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
        return Awaited.ready(ToolResult.ok("restarted"));
      }
    }
  }

  /**
   * There is no per-id wiring cache any more (§10.11): {@code bind(id)} binds a fresh handle from
   * the shared substrate on every call. This is the reform's whole point in one test — two
   * deliveries to the same scope, each through a brand-new binding, still see each other's history
   * because the substrate underneath persists it, not the (deleted) cache.
   */
  @Test
  void
      aSecondObservationToTheSameScopeSeesTheFirstsHistoryEvenThoughEveryDeliveryBindsAFreshHandle() {
    var pump = new PumpedExecutor();
    var provider =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.TextChunk("first reply")),
                List.of(new ModelEvent.TextChunk("second reply"))));
    ConcurrentMap<String, Memory> captured = new ConcurrentHashMap<>();

    var harness =
        Nessy.harness(
            h ->
                h.model(provider)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .executor(pump)
                    .memoryFactory(
                        id -> captured.computeIfAbsent(id, ignored -> new VerbatimMemory())));
    HarnessTeardown.track(harness);

    harness.bind(AgentId.of("scope-1")).tell("first message");
    pump.pumpUntilQuiet();
    harness.bind(AgentId.of("scope-1")).tell("second message");
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
   * in any object graph the config happens to wire together. Neither {@code memoryFactory} nor
   * {@code storeFactory} is overridden here — the harness uses its default {@code id -> new
   * SubstrateMemory(substrate, id)} recipe over the one substrate this test supplies — so the only
   * thing tying the two deliveries together is the shared {@link Substrate}. Proof is read back
   * through a SECOND, independently-constructed {@code SubstrateMemory} over that same substrate: a
   * fresh recipe instance, never touched by the harness, still recalls both turns.
   */
  @Test
  void twoDeliveriesToTheSameAgentShareOneSubstrateProvenByASecondMemoryBinding() {
    var pump = new PumpedExecutor();
    var provider =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.TextChunk("first reply")),
                List.of(new ModelEvent.TextChunk("second reply"))));
    var substrate = new InMemorySubstrate();

    var harness =
        Nessy.harness(
            h ->
                h.model(provider)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .executor(pump)
                    .substrate(substrate));
    HarnessTeardown.track(harness);

    harness.bind(AgentId.of("scope-1")).tell("first message");
    pump.pumpUntilQuiet();
    harness.bind(AgentId.of("scope-1")).tell("second message");
    pump.pumpUntilQuiet();

    // a fresh recipe instance the harness never held a reference to — the substrate, not the
    // object graph, is what makes this see both turns
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
   * The branch's headline claim, proven with two entirely separate harnesses rather than one
   * customization reused: durability lives in the {@link Substrate} itself, so a second harness —
   * built later, knowing nothing about the first — still inherits the first harness's turn the
   * moment it's pointed at the same substrate. The proof rides the model request harness B's own
   * provider recorded: its context carries harness A's turn.
   */
  @Test
  void aSecondHarnessBuiltOverTheSameSubstrateInheritsTheFirstHarnessesTurn() {
    var substrate = new InMemorySubstrate();

    var pumpA = new PumpedExecutor();
    var providerA = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("reply one"))));
    var harnessA =
        Nessy.harness(
            h ->
                h.model(providerA)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .executor(pumpA)
                    .substrate(substrate));
    HarnessTeardown.track(harnessA);
    harnessA.bind(AgentId.of("shared-scope")).tell("message one");
    pumpA.pumpUntilQuiet();

    var pumpB = new PumpedExecutor();
    var providerB = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("reply two"))));
    var harnessB =
        Nessy.harness(
            h ->
                h.model(providerB)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .executor(pumpB)
                    .substrate(substrate));
    HarnessTeardown.track(harnessB);
    harnessB.bind(AgentId.of("shared-scope")).tell("message two");
    pumpB.pumpUntilQuiet();

    List<ModelRequest> requestsToHarnessB = providerB.requests();
    assertThat(requestsToHarnessB).hasSize(1);
    List<Message> secondTurnContext = requestsToHarnessB.get(0).context().messages();
    assertThat(secondTurnContext)
        .isNotEmpty()
        .anyMatch(m -> m.content().contains(new TextBlock("message one")))
        .anyMatch(m -> m.content().contains(new TextBlock("reply one")));
  }

  /**
   * Task 5 (bytes-and-codecs): observations are typed — {@link Nessy#harness(Class,
   * HarnessCustomizer)} opens the typed door, the backlog codec defaults to {@code
   * Codec.json(pinned, observationType)} (spec §6.4).
   */
  /**
   * Typed-stores fix round 1, Q1: {@link HarnessConfig#finish()} derives the backlog codec from
   * {@code effectiveSubstrate.codecs()} — a caller-supplied {@link Substrate} must therefore be
   * just as format-pinned (lower-camel naming, tolerant reads, {@code ALWAYS} inclusion) as the
   * harness's own default substrate, or the stored backlog format floats depending on who
   * constructed the substrate.
   */
  @Nested
  class CallerSuppliedSubstrateStaysFormatPinned {

    record Note(String text, int priority) {}

    /**
     * A bare {@code new InMemorySubstrate()} — no manual pin call of its own, the exact caller
     * shape Q1's regression exposed — must still tolerate an unknown field on a stored backlog
     * element exactly as the default (no {@code .substrate(...)}) path does: {@code
     * FAIL_ON_UNKNOWN_PROPERTIES} pinned {@code false} is what {@link
     * org.jwcarman.nessy.spi.substrate.SubstrateSupport#copyAndPin} guarantees now regardless of
     * who constructed the substrate.
     */
    @Test
    void anExtraFieldOnAStoredBacklogElementIsToleratedOverACallerSuppliedSubstrate()
        throws JsonProcessingException {
      var substrate = new InMemorySubstrate();
      var scopeId = "scope-1";
      var pumpA = new PumpedExecutor();
      var providerA = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("reply"))));
      var harnessA =
          Nessy.harness(
              Note.class,
              h ->
                  h.model(providerA)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pumpA)
                      .substrate(substrate)
                      .renderer(note -> List.of(new TextBlock(note.text()))));
      HarnessTeardown.track(harnessA);

      // Primes the scope busy (Idle -> AwaitingModel); pumpA is never pumped, so the SECOND
      // tell below lands in the backlog document instead of draining immediately.
      harnessA.bind(AgentId.of(scopeId)).tell(new Note("prime", 1));
      harnessA.bind(AgentId.of(scopeId)).tell(new Note("check the oven", 3));

      // Rewrites the pending element's own JSON to carry an unknown "futureField" property — a
      // stored-format compatibility scenario (a newer schema version's payload read by older
      // code), not something this harness's own encode path would ever produce itself.
      Substrate.Document backlogDoc = substrate.read("backlog", scopeId).orElseThrow();
      ObjectMapper plain = TestMappers.plainlyPinned();
      String[] elements =
          plain.readValue(new String(backlogDoc.payload(), StandardCharsets.UTF_8), String[].class);
      assertThat(elements).hasSize(1);
      String innerJson =
          new String(Base64.getDecoder().decode(elements[0]), StandardCharsets.UTF_8);
      ObjectNode mutated = (ObjectNode) plain.readTree(innerJson);
      mutated.put("futureField", "not yet invented");
      elements[0] = Base64.getEncoder().encodeToString(plain.writeValueAsBytes(mutated));
      substrate.write("backlog", scopeId, plain.writeValueAsBytes(elements), backlogDoc.version());

      // harnessA is abandoned here: pumpA is never pumped.

      var pumpB = new PumpedExecutor();
      var providerB =
          new ScriptedModel(
              List.of(
                  List.of(new ModelEvent.TextChunk("reply to prime")),
                  List.of(new ModelEvent.TextChunk("reply to pending"))));
      var harnessB =
          Nessy.harness(
              Note.class,
              h ->
                  h.model(providerB)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pumpB)
                      .substrate(substrate)
                      .renderer(note -> List.of(new TextBlock(note.text())))
                      .staleness((phase, lastSaved) -> true));
      HarnessTeardown.track(harnessB);

      // Re-fires harness A's stuck turn; its completion drains the pending (now extra-field-
      // carrying) Note by the recipe's own drainOnIdle wiring — this throws if the caller-supplied
      // substrate's codec factory is not tolerant-read pinned.
      harnessB.bind(AgentId.of(scopeId)).drive();
      pumpB.pumpUntilQuiet();

      var memory = new SubstrateMemory(substrate, scopeId, TestMappers.plainlyPinned());
      List<Message> messages = memory.recall().messages();
      assertThat(messages)
          .isNotEmpty()
          .anyMatch(m -> m.content().contains(new TextBlock("check the oven")))
          .anyMatch(m -> m.content().contains(new TextBlock("reply to pending")));
    }
  }

  @Nested
  class TypedObservations {

    record Note(String text, int priority) {}

    /**
     * The headline claim: a typed observation posted while its scope is busy sits in the {@code
     * backlog} document — not drained, not lost — and is still there for a SECOND harness, built
     * later over the same substrate, knowing nothing about the first. Harness A primes the scope
     * busy with one observation (drained synchronously into an {@code AwaitingModel} turn its own,
     * never-pumped executor leaves permanently stuck) then observes the record under test, which
     * {@code drive()} declines to drain because the scope isn't {@code Idle}. Harness A is then
     * abandoned. Harness B — a fresh harness, fresh executor, fresh provider — is built over that
     * same {@link Substrate} with a staleness policy that treats the stuck phase as stale
     * immediately, so one {@code drive()} call re-fires harness A's stranded model call; that
     * turn's completion lands the scope back at {@code Idle}, which (by the recipe's own {@code
     * drainOnIdle} wiring) triggers the backlog drain that finally renders the pending {@code Note}
     * — round-tripped through the queue's {@code Codec<Note>} — and drives its own scripted turn.
     */
    @Test
    void aTypedRecordObservationSurvivesTheBacklogAcrossHarnessesAndDrivesAScriptedTurnOnHarnessB()
        throws JsonProcessingException {
      var substrate = new InMemorySubstrate();
      var scopeId = "scope-1";

      var pumpA = new PumpedExecutor();
      var providerA =
          new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("reply to prime"))));
      var harnessA =
          Nessy.harness(
              Note.class,
              h ->
                  h.model(providerA)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pumpA)
                      .substrate(substrate)
                      .renderer(note -> List.of(new TextBlock(note.text()))));
      HarnessTeardown.track(harnessA);

      // Primes the scope busy: drained synchronously (Idle -> AwaitingModel) as part of tell()
      // itself, dispatching a model-call effect onto pumpA — never pumped below, so harness A's
      // turn never completes and the scope is left stuck at AwaitingModel.
      harnessA.bind(AgentId.of(scopeId)).tell(new Note("prime", 1));

      var pending = new Note("check the oven", 3);
      // The scope isn't Idle any more, so drive() declines to drain this one — it sits in the
      // backlog document, the claim under test.
      harnessA.bind(AgentId.of(scopeId)).tell(pending);

      // The raw backlog document holds exactly the pending Note: "prime" already drained out as
      // part of the first tell, "check the oven" sat back down because the scope was busy.
      Substrate.Document backlogDoc = substrate.read("backlog", scopeId).orElseThrow();
      String[] backlogElements =
          TestMappers.plainlyPinned()
              .readValue(new String(backlogDoc.payload(), StandardCharsets.UTF_8), String[].class);
      assertThat(backlogElements).isNotEmpty().hasSize(1);

      // harnessA is abandoned here: pumpA is never pumped.

      var pumpB = new PumpedExecutor();
      var providerB =
          new ScriptedModel(
              List.of(
                  List.of(new ModelEvent.TextChunk("reply to prime")),
                  List.of(new ModelEvent.TextChunk("reply to pending"))));
      var harnessB =
          Nessy.harness(
              Note.class,
              h ->
                  h.model(providerB)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pumpB)
                      .substrate(substrate)
                      .renderer(note -> List.of(new TextBlock(note.text())))
                      .staleness((phase, lastSaved) -> true));
      HarnessTeardown.track(harnessB);

      // No new observation posted on harness B — drive() alone re-fires harness A's stuck turn;
      // its completion then drains the pending Note by the recipe's own drainOnIdle wiring.
      harnessB.bind(AgentId.of(scopeId)).drive();
      pumpB.pumpUntilQuiet();

      var memory = new SubstrateMemory(substrate, scopeId, TestMappers.plainlyPinned());
      List<Message> messages = memory.recall().messages();
      assertThat(messages)
          .isNotEmpty()
          .anyMatch(m -> m.content().contains(new TextBlock("prime")))
          .anyMatch(m -> m.content().contains(new TextBlock("reply to prime")))
          .anyMatch(m -> m.content().contains(new TextBlock("check the oven")))
          .anyMatch(m -> m.content().contains(new TextBlock("reply to pending")));

      var state =
          new SubstrateAgentStateStore(
              substrate, scopeId, Clock.systemUTC(), TestMappers.plainlyPinned());
      assertThat(state.load().phase()).isEqualTo(new Phase.Idle());
    }

    /**
     * The typed door's required seam: {@link HarnessConfig#renderer} has no default for {@code O},
     * unlike the {@code String} door's preset lambda — a customizer that never calls it fails
     * loudly, naming {@code renderer}.
     */
    @Test
    void theTypedDoorWithoutARendererIsRejectedNamingTheRenderer() {
      // fix round 1 M4: hoisted out of the assertThatThrownBy lambda (S5778) — only Nessy.harness
      // itself may throw below.
      var provider = new ScriptedModel(List.of());
      var settings = TestSettings.settings();

      assertThatThrownBy(
              () ->
                  Nessy.harness(
                      Note.class,
                      h ->
                          h.model(provider)
                              .systemPrompt(TestSettings.SYSTEM_PROMPT)
                              .settings(settings)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("renderer must not be null")
          .hasMessageContaining(".renderer(ObservationRenderer)");
    }

    /**
     * {@link Nessy#harness(Class, HarnessCustomizer)} rejects a null observation type up front,
     * naming it.
     */
    @Test
    void aNullObservationTypeIsRejectedByTheTypedDoor() {
      Class<Note> nullType = null;

      assertThatThrownBy(() -> Nessy.harness(nullType, h -> {}))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("observationType");
    }
  }

  /**
   * Task 3 (bytes-and-codecs): one mapper in, pinned copy throughout (spec §7). {@code
   * .objectMapper(ObjectMapper)} feeds the finished harness's one pinned copy into every recipe
   * that binds JSON — the tool-call binder included.
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
          new ScriptedModel(
              List.of(
                  List.of(new ModelEvent.ToolUseEmitted(call, null)),
                  List.of(new ModelEvent.TextChunk("charged"))));
      var observer = new RecordingTurnObserver();

      var harness =
          Nessy.harness(
              h ->
                  h.model(provider)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pump)
                      .objectMapper(userMapper)
                      .tools(new ChargeTool())
                      .turnObserver(observer));
      HarnessTeardown.track(harness);

      harness.bind(AgentId.of("scope-1")).tell("charge please");
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
     * the finished harness pins lower-camel property naming on its copy (spec §7), so the
     * substrate's stored JSON stays camelCase — {@code ToolResultBlock}'s golden fields, {@code
     * toolUseId} and {@code isError} — regardless of what the caller's mapper prefers.
     */
    @Test
    void theHarnessPinsTheStoredFormatEvenWhenTheUsersMapperPrefersSnakeCase() {
      var substrate = new InMemorySubstrate();
      var snakeCaseMapper =
          new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
      var pump = new PumpedExecutor();
      var call =
          new ToolCall("c1", "echo", JsonNodeFactory.instance.objectNode().put("value", "hi"));
      var provider =
          new ScriptedModel(
              List.of(
                  List.of(new ModelEvent.ToolUseEmitted(call, null)),
                  List.of(new ModelEvent.TextChunk("done"))));

      var harness =
          Nessy.harness(
              h ->
                  h.model(provider)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pump)
                      .substrate(substrate)
                      .objectMapper(snakeCaseMapper)
                      .tools(new EchoTool()));
      HarnessTeardown.track(harness);

      harness.bind(AgentId.of("scope-1")).tell("hi");
      pump.pumpUntilQuiet();

      List<Substrate.Entry> entries = substrate.entries("memory", "scope-1", 1);
      assertThat(entries).isNotEmpty();
      String allPayloads =
          entries.stream()
              .map(e -> new String(e.payload(), StandardCharsets.UTF_8))
              .collect(Collectors.joining("\n"));
      // Remembrance spec §2: a completed tool call stores as a ToolExchange (the call and its
      // result, paired), not a bare ToolResultBlock — so "toolUseId" no longer appears on the
      // wire here; "isError" (from the exchange's result) still pins camelCase either way.
      assertThat(allPayloads).contains("\"isError\"").doesNotContain("is_error");
    }

    @Test
    void aNullObjectMapperIsRejectedByItsSetter() {
      assertThatThrownBy(() -> Nessy.harness(h -> h.objectMapper(null)))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
