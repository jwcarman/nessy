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
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentPhaseStore;
import org.jwcarman.nessy.agent.store.SubstrateAgentPhaseStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.NoToolsExecutor;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.agent.AgentType;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.api.turn.Subscription;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.memory.SubstrateMemory;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * {@link Agent#subscribe(TurnObserver)} (front-ends spec §2): the harness-internal per-id fanout,
 * proven worker-inclusive (a delivery folding on the harness's own worker still reaches a
 * subscriber registered before the park), scoped (two ids never cross), closeable (idempotent, and
 * stops further delivery), and isolating (a throwing subscriber never poisons the fold for anyone
 * else). Every harness here is built through {@link Harness#of}'s real factories — the same wiring
 * {@link org.jwcarman.nessy.agent.host.HarnessConfig} composes — so a subscription exercises the
 * genuine per-id fanout, not a test double that ignores the {@link TurnObserver} a factory is
 * handed.
 */
class AgentSubscriptionTest {

  /** Any term: nothing in this test clips it. */
  private static final Duration TERM = Duration.ofDays(7);

  /** The harness ceilings, as HarnessConfig sets them (deferral-by-callback spec §5). */
  private static final Duration APPROVAL_CEILING = Duration.ofDays(7);

  private static final Duration TOOL_CEILING = Duration.ofDays(1);

  @AfterEach
  void shutdownTrackedHarnesses() {
    HarnessTeardown.shutdownAllTracked();
  }

  record NoInput() {}

  /** A tool gated behind approval — records every actual execution. */
  private static final class RecordingTool implements Tool<NoInput> {

    private final AtomicInteger invocations = new AtomicInteger();

    @Override
    public String name() {
      return "restart";
    }

    @Override
    public String description() {
      return "gated behind approval";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      invocations.incrementAndGet();
      return Awaited.ready(ToolResult.ok("restarted"));
    }
  }

  private static final class QueueBacklog implements Backlog<String> {
    private final Deque<String> queue = new ArrayDeque<>();

    @Override
    public void add(String observation) {
      queue.add(observation);
    }

    @Override
    public Optional<String> poll() {
      return Optional.ofNullable(queue.poll());
    }
  }

  /**
   * A no-tool harness over a scripted, text-only model: every {@code tell} that reaches the model
   * narrates its deltas synchronously once {@code pump} is drained — no approval, no worker fold,
   * just enough real wiring to prove the fanout routes correctly by id.
   */
  private static Harness<String> chatHarness(
      AgentType type, ScriptedModel model, PumpedExecutor pump) {
    var mapper = TestMappers.plainlyPinned();
    var substrate = new InMemorySubstrate();
    var approvalClient = TestApprovalClients.client(Kinds.approval(type), mapper);
    var toolClient = TestToolClients.client(Kinds.tool(type), mapper);
    ToolCallExecutor noTools = new NoToolsExecutor();
    Harness<String> harness =
        Harness.of(
            type,
            "test_provider",
            "test-model",
            text -> List.of(new TextBlock(text)),
            List.of(HarnessObserver.noop()),
            TurnObserver.noop(),
            false,
            StalenessPolicy.never(),
            rawId -> new SubstrateMemory(substrate, rawId, mapper),
            rawId -> new SubstrateAgentPhaseStore(substrate, rawId, Clock.systemUTC(), mapper),
            rawId -> new QueueBacklog(),
            (scopeId, turnObserver) ->
                new ProviderModelCallExecutor(
                    model,
                    TestSettings.SYSTEM_PROMPT,
                    TestSettings.settings(),
                    TestSettings.emptyRegistry(),
                    new SubstrateMemory(substrate, scopeId.value(), mapper),
                    turnObserver,
                    pump,
                    ObservationRegistry.NOOP,
                    () -> null),
            (id, turnObserver) -> noTools,
            substrate,
            mapper,
            approvalClient,
            toolClient,
            new ConcurrentHashMap<>(),
            ObservationRegistry.NOOP,
            new ConcurrentHashMap<>());
    HarnessTeardown.track(harness);
    return harness;
  }

  @Nested
  class WorkerInclusiveDelivery {

    /**
     * The brief's central proof: subscribe before the turn parks on an approval, then grant it
     * through {@link ApprovalDesk} — the same door a production caller uses — which nudges the
     * harness's own daemon-threaded {@link DeliveryWorker}. The resumed turn's events (the tool's
     * completion, then the second model call's reply) both reach the id's subscriber, even though
     * neither runs on the {@link Agent} handle {@code tell} was originally called on — the worker
     * folds through its own, freshly-bound {@link DefaultAgent} for the same id.
     */
    @Test
    void
        a_subscriber_registered_before_a_park_sees_the_turn_that_resumes_after_the_worker_folds_the_grant()
            throws InterruptedException {
      var mapper = TestMappers.plainlyPinned();
      var substrate = new InMemorySubstrate();
      var type = AgentType.of("subscription-worker");
      var approvalClient = TestApprovalClients.client(Kinds.approval(type), mapper);
      var toolClient = TestToolClients.client(Kinds.tool(type), mapper);
      var notifications = new CopyOnWriteArrayList<ComputationId>();
      // The notification is the CALLBACK now: it runs once the harness has parked the question,
      // which is the only moment an id exists (deferral-by-callback spec §1).
      Approver deferring =
          context -> ApprovalOutcome.deferred((id, deadline) -> notifications.add(id), TERM);
      var tool = new RecordingTool();
      var registry = ToolRegistry.of(ToolGrant.grant(tool, deferring));
      var pump = new PumpedExecutor();
      var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
      var model =
          new ScriptedModel(
              List.of(
                  List.of(new ModelEvent.ToolUseEmitted(call, null)),
                  List.of(new ModelEvent.TextChunk("done"))));
      java.util.function.Function<String, AgentPhaseStore> storeFactory =
          rawId -> new SubstrateAgentPhaseStore(substrate, rawId, Clock.systemUTC(), mapper);

      Harness<String> harness =
          Harness.of(
              type,
              "test_provider",
              "test-model",
              text -> List.of(new TextBlock(text)),
              List.of(HarnessObserver.noop()),
              TurnObserver.noop(),
              false,
              StalenessPolicy.never(),
              rawId -> new SubstrateMemory(substrate, rawId, mapper),
              storeFactory,
              rawId -> new QueueBacklog(),
              (scopeId, turnObserver) ->
                  new ProviderModelCallExecutor(
                      model,
                      TestSettings.SYSTEM_PROMPT,
                      TestSettings.settings(),
                      registry,
                      new SubstrateMemory(substrate, scopeId.value(), mapper),
                      turnObserver,
                      pump,
                      ObservationRegistry.NOOP,
                      () -> null),
              (id, turnObserver) ->
                  new RegistryToolCallExecutor(
                      registry,
                      type,
                      id,
                      turnObserver,
                      pump,
                      approvalClient,
                      toolClient,
                      mapper,
                      ObservationRegistry.NOOP,
                      () -> null,
                      APPROVAL_CEILING,
                      TOOL_CEILING),
              substrate,
              mapper,
              approvalClient,
              toolClient,
              new ConcurrentHashMap<>(),
              ObservationRegistry.NOOP,
              new ConcurrentHashMap<>());
      HarnessTeardown.track(harness);

      var agent = harness.bind(AgentId.of("scope-a"));
      var recorder = new RecordingTurnObserver();

      try (Subscription subscription = agent.subscribe(recorder)) {
        agent.tell("please restart");
        pump.pumpUntilQuiet();

        assertThat(notifications).hasSize(1); // parked: the tool call is awaiting approval

        // answers and nudges the worker
        harness.approvals().approve(notifications.getFirst(), "test", "");
        // approve() only submits the drain now (continuum-adoption spec §7): the fold runs on the
        // harness's own ComputationScheduler thread, which dispatches the resumed model call onto
        // `pump` from that background thread at some point AFTER the tool itself ran — polling on
        // the tool's own invocation count would race that later enqueue, so this awaits the turn's
        // own resumption (Idle) instead, the definitive final signal.
        var scopeAState = storeFactory.apply("scope-a");
        long deadline = System.currentTimeMillis() + 5000;
        while (!(scopeAState.load().value() instanceof AgentPhase.Idle)
            && System.currentTimeMillis() < deadline) {
          pump.pumpUntilQuiet();
          Thread.sleep(20);
        }

        assertThat(tool.invocations).hasValue(1);
        List<TurnEvent> events = recorder.events();
        assertThat(events).isNotEmpty();
        assertThat(events)
            .anySatisfy(
                event ->
                    assertThat(event)
                        .isInstanceOfSatisfying(
                            TurnEvent.ToolCallCompleted.class,
                            completed -> assertThat(completed.result().isError()).isFalse()));
        assertThat(events)
            .anySatisfy(
                event ->
                    assertThat(event)
                        .isInstanceOfSatisfying(
                            TurnEvent.TextDelta.class,
                            delta -> assertThat(delta.text()).isEqualTo("done")));
      }
    }
  }

  @Nested
  class Scoping {

    @Test
    void two_agent_ids_on_the_same_harness_never_cross_each_others_events() {
      var pump = new PumpedExecutor();
      var type = AgentType.of("subscription-scoping");
      var model =
          new ScriptedModel(
              List.of(
                  List.of(new ModelEvent.TextChunk("for a")),
                  List.of(new ModelEvent.TextChunk("for b"))));
      Harness<String> harness = chatHarness(type, model, pump);

      var agentA = harness.bind(AgentId.of("scope-a"));
      var agentB = harness.bind(AgentId.of("scope-b"));
      var recorderA = new RecordingTurnObserver();
      var recorderB = new RecordingTurnObserver();

      try (Subscription subscriptionA = agentA.subscribe(recorderA);
          Subscription subscriptionB = agentB.subscribe(recorderB)) {
        agentA.tell("hello a");
        pump.pumpUntilQuiet();
        agentB.tell("hello b");
        pump.pumpUntilQuiet();

        assertThat(recorderA.events()).isNotEmpty().noneMatch(Scoping::sawTextForB);
        assertThat(recorderB.events()).isNotEmpty().noneMatch(Scoping::sawTextForA);
      }
    }

    private static boolean sawTextForB(TurnEvent event) {
      return event instanceof TurnEvent.TextDelta(String text) && text.equals("for b");
    }

    private static boolean sawTextForA(TurnEvent event) {
      return event instanceof TurnEvent.TextDelta(String text) && text.equals("for a");
    }
  }

  @Nested
  class CloseIsIdempotentAndStopsDelivery {

    @Test
    void closing_a_subscription_twice_is_a_silent_no_op() {
      var pump = new PumpedExecutor();
      var type = AgentType.of("subscription-close-idempotent");
      var model = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hi"))));
      Harness<String> harness = chatHarness(type, model, pump);
      var agent = harness.bind(AgentId.of("scope-a"));
      var recorder = new RecordingTurnObserver();

      Subscription subscription = agent.subscribe(recorder);
      subscription.close();

      assertThatCode(subscription::close).doesNotThrowAnyException();
    }

    @Test
    void a_closed_subscription_receives_no_further_events() {
      var pump = new PumpedExecutor();
      var type = AgentType.of("subscription-close-stops-delivery");
      var model =
          new ScriptedModel(
              List.of(
                  List.of(new ModelEvent.TextChunk("first")),
                  List.of(new ModelEvent.TextChunk("second"))));
      Harness<String> harness = chatHarness(type, model, pump);
      var agent = harness.bind(AgentId.of("scope-a"));
      var recorder = new RecordingTurnObserver();

      Subscription subscription = agent.subscribe(recorder);
      agent.tell("go once");
      pump.pumpUntilQuiet();
      int seenBeforeClose = recorder.events().size();
      assertThat(seenBeforeClose).isPositive();

      subscription.close();
      agent.tell("go twice");
      pump.pumpUntilQuiet();

      assertThat(recorder.events()).hasSize(seenBeforeClose);
    }
  }

  @Nested
  class SubscriberThrowIsolation {

    /**
     * Fix round 1, MINOR-4: asserts the scope itself lands on {@link AgentPhase.Idle} — the same
     * shape {@code
     * NessyHarnessDoorTest.aThrowingTurnObserverDoesNotStallTheScopesEffectsOrCompletion} checks
     * for the harness's global observer — so "never poisons the fold" is proven regardless of where
     * in subscribe order the throwing observer sits, not just proven for the OTHER subscriber's
     * event count.
     */
    @Test
    void a_throwing_subscriber_is_isolated_and_never_poisons_the_fold_for_other_subscribers() {
      var mapper = TestMappers.plainlyPinned();
      var substrate = new InMemorySubstrate();
      var type = AgentType.of("subscription-throw-isolation");
      var approvalClient = TestApprovalClients.client(Kinds.approval(type), mapper);
      var toolClient = TestToolClients.client(Kinds.tool(type), mapper);
      var pump = new PumpedExecutor();
      var model = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hi"))));
      ToolCallExecutor noTools = new NoToolsExecutor();
      String scopeId = "scope-a";

      Harness<String> harness =
          Harness.of(
              type,
              "test_provider",
              "test-model",
              text -> List.of(new TextBlock(text)),
              List.of(HarnessObserver.noop()),
              TurnObserver.noop(),
              false,
              StalenessPolicy.never(),
              rawId -> new SubstrateMemory(substrate, rawId, mapper),
              rawId -> new SubstrateAgentPhaseStore(substrate, rawId, Clock.systemUTC(), mapper),
              rawId -> new QueueBacklog(),
              (boundScope, turnObserver) ->
                  new ProviderModelCallExecutor(
                      model,
                      TestSettings.SYSTEM_PROMPT,
                      TestSettings.settings(),
                      TestSettings.emptyRegistry(),
                      new SubstrateMemory(substrate, boundScope.value(), mapper),
                      turnObserver,
                      pump,
                      ObservationRegistry.NOOP,
                      () -> null),
              (id, turnObserver) -> noTools,
              substrate,
              mapper,
              approvalClient,
              toolClient,
              new ConcurrentHashMap<>(),
              ObservationRegistry.NOOP,
              new ConcurrentHashMap<>());
      HarnessTeardown.track(harness);

      var agent = harness.bind(AgentId.of(scopeId));
      var wellBehaved = new RecordingTurnObserver();
      TurnObserver throwing =
          event -> {
            throw new RuntimeException("subscriber boom");
          };

      try (Subscription throwingSubscription = agent.subscribe(throwing);
          Subscription wellBehavedSubscription = agent.subscribe(wellBehaved)) {
        agent.tell("go");
        pump.pumpUntilQuiet();
      }

      assertThat(wellBehaved.events()).isNotEmpty();
      var scopeState = new SubstrateAgentPhaseStore(substrate, scopeId, Clock.systemUTC(), mapper);
      assertThat(scopeState.load().value()).isEqualTo(new AgentPhase.Idle());
    }
  }

  @Nested
  class GlobalObserverRunsLast {

    /**
     * Fix round 2, I4: pins {@link TurnFanout}'s ordering the other direction from {@link
     * SubscriberThrowIsolation} — a throwing GLOBAL observer (the harness's own configured {@code
     * turnObserver}) must never starve a {@code subscribe}d recorder of the same event, because the
     * fanout delivers to every subscriber first, individually isolated, and only THEN to the global
     * observer, unguarded. If the ordering were ever reversed, a throwing global would abort the
     * narration before the subscriber saw anything.
     */
    @Test
    void a_throwing_global_observer_never_starves_a_subscribed_recorder_of_the_same_event() {
      var mapper = TestMappers.plainlyPinned();
      var substrate = new InMemorySubstrate();
      var type = AgentType.of("subscription-global-throw");
      var approvalClient = TestApprovalClients.client(Kinds.approval(type), mapper);
      var toolClient = TestToolClients.client(Kinds.tool(type), mapper);
      var pump = new PumpedExecutor();
      var model = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hi"))));
      ToolCallExecutor noTools = new NoToolsExecutor();
      String scopeId = "scope-a";
      TurnObserver throwingGlobal =
          event -> {
            if (event instanceof TurnEvent.AssistantSaid) {
              throw new RuntimeException("global boom");
            }
          };

      Harness<String> harness =
          Harness.of(
              type,
              "test_provider",
              "test-model",
              text -> List.of(new TextBlock(text)),
              List.of(),
              throwingGlobal,
              false,
              StalenessPolicy.never(),
              rawId -> new SubstrateMemory(substrate, rawId, mapper),
              rawId -> new SubstrateAgentPhaseStore(substrate, rawId, Clock.systemUTC(), mapper),
              rawId -> new QueueBacklog(),
              (boundScope, turnObserver) ->
                  new ProviderModelCallExecutor(
                      model,
                      TestSettings.SYSTEM_PROMPT,
                      TestSettings.settings(),
                      TestSettings.emptyRegistry(),
                      new SubstrateMemory(substrate, boundScope.value(), mapper),
                      turnObserver,
                      pump,
                      ObservationRegistry.NOOP,
                      () -> null),
              (id, turnObserver) -> noTools,
              substrate,
              mapper,
              approvalClient,
              toolClient,
              new ConcurrentHashMap<>(),
              ObservationRegistry.NOOP,
              new ConcurrentHashMap<>());
      HarnessTeardown.track(harness);

      var agent = harness.bind(AgentId.of(scopeId));
      var recorder = new RecordingTurnObserver();

      try (Subscription subscription = agent.subscribe(recorder)) {
        agent.tell("go");
        pump.pumpUntilQuiet();
      }

      assertThat(recorder.events())
          .isNotEmpty()
          .anyMatch(event -> event instanceof TurnEvent.AssistantSaid);
    }
  }

  @Nested
  class ClosingLeaksNothing {

    /**
     * Fix round 1, IMPORTANT-1: spec §2 says only an UNCLOSED subscription leaks a routing entry —
     * a closed one must leak nothing at all, including the map entry itself. Proven through {@link
     * Harness#hasSubscribers(AgentId)}, a package-private test seam onto the internal registry, not
     * by inference from behavior.
     */
    @Test
    void closing_the_last_subscription_for_an_id_removes_its_registry_entry_entirely() {
      var pump = new PumpedExecutor();
      var type = AgentType.of("subscription-leak-check");
      var model = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hi"))));
      Harness<String> harness = chatHarness(type, model, pump);
      var id = AgentId.of("scope-a");
      var agent = harness.bind(id);
      var recorder = new RecordingTurnObserver();

      assertThat(harness.hasSubscribers(id)).isFalse();

      Subscription subscription = agent.subscribe(recorder);
      assertThat(harness.hasSubscribers(id)).isTrue();

      subscription.close();

      assertThat(harness.hasSubscribers(id)).isFalse();
    }
  }
}
