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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.store.SubstrateAgentPhaseStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.NoToolsExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.agent.AgentType;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.memory.VerbatimMemory;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Versioned;
import org.slf4j.LoggerFactory;

/**
 * Telemetry describes the work; it never participates in it (fix round 1, the critical finding).
 *
 * <p>An {@code ObservationHandler} is arbitrary application code running inline on whichever thread
 * started or stopped a span. Before this round, one that threw took the turn down with it: a tool
 * ran to completion and its {@code ToolFinished} was never delivered, a model call that had already
 * answered folded as {@code Failed}, and a CAS retry loop aborted on the very contention it exists
 * to converge past. Every one of those is proven here to be contained instead — the work completes,
 * the failure is logged exactly once, and nothing downstream can tell the difference.
 *
 * <p>The handler that named the rule is {@link ReadsUsageOnStop}: an application recording the
 * semconv {@code gen_ai.client.token.usage} metric reads {@code gen_ai.usage.input_tokens} off
 * every {@code chat} it stops — and a chat that failed before the model reported any usage carries
 * no such key, so the perfectly reasonable handler throws {@link NullPointerException} on the one
 * turn that was already having a bad day.
 */
class InstrumentationNeverBreaksATurnTest {

  /** The harness ceilings, as HarnessConfig sets them (deferral-by-callback spec §5). */
  private static final Duration APPROVAL_CEILING = Duration.ofDays(7);

  private static final Duration TOOL_CEILING = Duration.ofDays(1);

  /**
   * The Micrometer NAMES of the two executor-minted observations — semconv's own per-operation
   * duration histograms, NOT the span names {@code chat}/{@code execute_tool} that ride as
   * contextual names. A handler matches on {@code context.getName()}, so a test naming the span
   * here would match nothing and pass vacuously.
   */
  private static final String CHAT_METER = "gen_ai.client.operation.duration";

  private static final String EXECUTE_TOOL_METER = "gen_ai.execute_tool.duration";

  private static final AgentId SCOPE = AgentId.of("prod-eu");
  private static final ToolCall RESTART =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final TestObservationRegistry registry = TestObservationRegistry.create();
  private final List<ListAppender<ILoggingEvent>> appenders = new ArrayList<>();

  @AfterEach
  void tearDown() {
    HarnessTeardown.shutdownAllTracked();
    appenders.forEach(ListAppender::stop);
  }

  /** Captures {@code type}'s own WARNs, the technique the delivery-worker tests already use. */
  private List<ILoggingEvent> warningsFrom(Class<?> type) {
    Logger classicLogger = (Logger) LoggerFactory.getLogger(type);
    classicLogger.setLevel(Level.TRACE);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    classicLogger.addAppender(appender);
    appenders.add(appender);
    return appender.list;
  }

  private static List<ILoggingEvent> warnings(List<ILoggingEvent> captured) {
    return captured.stream().filter(event -> event.getLevel() == Level.WARN).toList();
  }

  // ---------------------------------------------------------------- handlers that misbehave

  /** Throws when an observation of {@code name} starts; silent otherwise. */
  private record ThrowsOnStart(String name) implements ObservationHandler<Observation.Context> {

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }

    @Override
    public void onStart(Observation.Context context) {
      if (name.equals(context.getName())) {
        throw new IllegalStateException("this handler explodes on start");
      }
    }
  }

  /** Throws when an observation of {@code name} stops; silent otherwise. */
  private record ThrowsOnStop(String name) implements ObservationHandler<Observation.Context> {

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }

    @Override
    public void onStop(Observation.Context context) {
      if (name.equals(context.getName())) {
        throw new IllegalStateException("this handler explodes on stop");
      }
    }
  }

  /**
   * The realistic one: an application deriving {@code gen_ai.client.token.usage} from the {@code
   * chat} span, which assumes every chat reported usage. On a chat that failed before {@code
   * TurnEnded} the key is absent and this throws.
   */
  private static final class ReadsUsageOnStop implements ObservationHandler<Observation.Context> {

    private final List<Long> recorded = new ArrayList<>();

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }

    @Override
    public void onStop(Observation.Context context) {
      if (CHAT_METER.equals(context.getName())) {
        recorded.add(
            Long.parseLong(
                context.getHighCardinalityKeyValue("gen_ai.usage.input_tokens").getValue()));
      }
    }
  }

  // ---------------------------------------------------------------- fixtures

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

  /** A model whose stream throws before it ever yields an event. */
  private static final class ExplodingModel implements Model {

    @Override
    public ModelStream stream(ModelRequest request) {
      throw new IllegalStateException("context overflow");
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }

    @Override
    public String id() {
      return "exploding";
    }

    @Override
    public String provider() {
      return "scripted";
    }
  }

  private record NoInput() {}

  private static final class EchoTool implements Tool<NoInput> {

    @Override
    public String name() {
      return "restart";
    }

    @Override
    public String description() {
      return "echoes, for the containment tests";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("restarted"));
    }
  }

  /**
   * A {@link Memory} that bumps the scope's stored state version the first {@code count} times it
   * is asked to remember something — a genuine second writer landing between the fold's read and
   * its CAS write, which is exactly the race the retry loop exists for. The phase it writes back is
   * the one already stored, so the retry re-handles against unchanged state and converges.
   */
  private static final class SecondWriter implements Memory {

    private final SubstrateAgentPhaseStore store;
    private final AtomicInteger remaining;

    private SecondWriter(SubstrateAgentPhaseStore store, int count) {
      this.store = store;
      this.remaining = new AtomicInteger(count);
    }

    @Override
    public void remember(Remembrance remembrance) {
      if (remaining.getAndDecrement() > 0) {
        Versioned<AgentPhase> current = store.load();
        store.save(new Versioned<>(current.value(), current.version()));
      }
    }

    @Override
    public Context recall() {
      return Context.of(List.of());
    }
  }

  @Nested
  class TheChatSpan {

    private ProviderModelCallExecutor executorOver(Model model) {
      return new ProviderModelCallExecutor(
          model,
          TestSettings.SYSTEM_PROMPT,
          TestSettings.settings(),
          TestSettings.emptyRegistry(),
          new VerbatimMemory(),
          TurnObserver.noop(),
          Runnable::run,
          registry,
          () -> null);
    }

    private ModelOutcome outcomeOf(Model model) {
      AtomicReference<AgentEvent> delivered = new AtomicReference<>();
      Sink sink = delivered::set;
      executorOver(model).callModel(sink);
      return ((AgentEvent.ModelFinished) delivered.get()).outcome();
    }

    @Test
    void a_handler_that_throws_starting_chat_leaves_the_model_call_answered() {
      List<ILoggingEvent> captured = warningsFrom(ProviderModelCallExecutor.class);
      registry.observationConfig().observationHandler(new ThrowsOnStart(CHAT_METER));
      Model model =
          new ScriptedModel(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("hello back"),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(10, 2, 0, 0)))));

      ModelOutcome outcome = outcomeOf(model);

      assertThat(outcome)
          .isInstanceOfSatisfying(
              ModelOutcome.Responded.class,
              responded -> assertThat(responded.content()).contains(new TextBlock("hello back")));
      assertThat(warnings(captured)).hasSize(1);
      assertThat(warnings(captured).getFirst().getThrowableProxy().getMessage())
          .isEqualTo("this handler explodes on start");
    }

    @Test
    void a_handler_that_throws_stopping_chat_leaves_the_model_call_answered() {
      List<ILoggingEvent> captured = warningsFrom(ProviderModelCallExecutor.class);
      registry.observationConfig().observationHandler(new ThrowsOnStop(CHAT_METER));
      Model model =
          new ScriptedModel(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("hello back"),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(10, 2, 0, 0)))));

      ModelOutcome outcome = outcomeOf(model);

      assertThat(outcome).isInstanceOf(ModelOutcome.Responded.class);
      assertThat(warnings(captured)).hasSize(1);
    }

    /**
     * The reviewer's case, end to end: a failing model call carries no usage, the application's
     * token handler reads it anyway and throws inside {@code onStop} — and the turn still folds the
     * {@code Failed} outcome it was going to fold, rather than the handler's NullPointerException
     * escaping onto the executor thread.
     */
    @Test
    void a_usage_reading_handler_survives_a_chat_that_never_reported_usage() {
      List<ILoggingEvent> captured = warningsFrom(ProviderModelCallExecutor.class);
      var tokenHandler = new ReadsUsageOnStop();
      registry.observationConfig().observationHandler(tokenHandler);

      ModelOutcome outcome = outcomeOf(new ExplodingModel());

      assertThat(outcome)
          .isInstanceOfSatisfying(
              ModelOutcome.Failed.class,
              failed -> assertThat(failed.reason()).contains("context overflow"));
      assertThat(tokenHandler.recorded).isEmpty();
      assertThat(warnings(captured)).hasSize(1);
      assertThat(warnings(captured).getFirst().getThrowableProxy().getClassName())
          .isEqualTo(NullPointerException.class.getName());
    }
  }

  @Nested
  class TheExecuteToolSpan {

    private AgentEvent runToolThrough(ObservationHandler<Observation.Context> handler) {
      registry.observationConfig().observationHandler(handler);
      var tools =
          new RegistryToolCallExecutor(
              ToolRegistry.of(new EchoTool()),
              AgentType.of("test"),
              SCOPE,
              TurnObserver.noop(),
              Runnable::run,
              TestApprovalClients.client("approval/test", mapper),
              TestToolClients.client("tool/test", mapper),
              mapper,
              registry,
              () -> null,
              APPROVAL_CEILING,
              TOOL_CEILING);
      AtomicReference<AgentEvent> delivered = new AtomicReference<>();
      tools.runTool(RESTART, ModelResponseId.of("r1"), delivered::set);
      return delivered.get();
    }

    @Test
    void a_handler_that_throws_starting_execute_tool_still_delivers_the_real_result() {
      List<ILoggingEvent> captured = warningsFrom(RegistryToolCallExecutor.class);

      AgentEvent event = runToolThrough(new ThrowsOnStart(EXECUTE_TOOL_METER));

      assertThat(event)
          .isInstanceOfSatisfying(
              AgentEvent.ToolFinished.class,
              finished ->
                  assertThat(finished.outcome())
                      .isEqualTo(new ToolOutcome.Returned(ToolResult.ok("restarted"))));
      assertThat(warnings(captured)).hasSize(1);
    }

    @Test
    void a_handler_that_throws_stopping_execute_tool_still_delivers_the_real_result() {
      List<ILoggingEvent> captured = warningsFrom(RegistryToolCallExecutor.class);

      AgentEvent event = runToolThrough(new ThrowsOnStop(EXECUTE_TOOL_METER));

      assertThat(event)
          .isInstanceOfSatisfying(
              AgentEvent.ToolFinished.class,
              finished ->
                  assertThat(finished.outcome())
                      .isEqualTo(new ToolOutcome.Returned(ToolResult.ok("restarted"))));
      assertThat(warnings(captured)).hasSize(1);
    }
  }

  @Nested
  class TheStaleRetryCounter {

    /**
     * The nastiest of the three: this counter is recorded from INSIDE the retry loop, so a throwing
     * handler would abort the loop on the very contention the loop exists to converge past — and
     * the observation that lost the race would be dropped rather than retried.
     */
    @Test
    void a_handler_that_throws_recording_a_stale_retry_still_lets_the_shell_converge() {
      // Observations is where the counter's own containment lives, so that is where the WARN
      // lands — the fold sites call staleRetry on its documented never-throws contract.
      List<ILoggingEvent> captured = warningsFrom(Observations.class);
      registry
          .observationConfig()
          .observationHandler(new ThrowsOnStart("nessy.state.stale_retries"));
      var substrate = new InMemorySubstrate();
      var store = new SubstrateAgentPhaseStore(substrate, SCOPE.value(), Clock.systemUTC(), mapper);
      var contender =
          new SubstrateAgentPhaseStore(substrate, SCOPE.value(), Clock.systemUTC(), mapper);
      DefaultHarness<String> harness =
          TestAgents.harness(
              AgentType.of("test"),
              new SecondWriter(contender, 1),
              store,
              new QueueBacklog(),
              text -> List.of(new TextBlock(text)),
              sink -> {},
              new NoToolsExecutor(),
              HarnessObserver.noop(),
              false,
              StalenessPolicy.never(),
              registry);

      harness.bind(SCOPE).tell("restart prod-eu");

      // Converged: the losing write was retried and the observation is folded, not lost.
      assertThat(store.load().value()).isInstanceOf(AgentPhase.AwaitingModel.class);
      assertThat(warnings(captured)).hasSize(1);
    }
  }

  @Nested
  class TheDroppedDeliveryCounter {

    /**
     * {@code Observations} publishes its counters straight from the fold sites, outside {@link
     * FactFanout}'s own subscriber isolation, so it contains them itself.
     */
    @Test
    void a_handler_that_throws_recording_a_dropped_delivery_never_reaches_the_fold() {
      List<ILoggingEvent> captured = warningsFrom(Observations.class);
      registry.observationConfig().observationHandler(new ThrowsOnStart("nessy.delivery.dropped"));
      var observations =
          new Observations(
              registry,
              AgentType.of("test"),
              "test_provider",
              "test-model",
              new ConcurrentHashMap<>());

      // An Idle scope ignoring a delivered tool result: the dropped-delivery counter's own arm.
      observations.ignored(
          SCOPE,
          new AgentEvent.ToolFinished(
              RESTART,
              Optional.of(ComputationId.of("tool-1")),
              new ToolOutcome.Returned(ToolResult.ok("late"))));

      assertThat(warnings(captured)).hasSize(1);
    }
  }
}
