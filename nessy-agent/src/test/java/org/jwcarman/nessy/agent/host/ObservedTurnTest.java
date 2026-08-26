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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * The roster, end to end through the one door (agentic-o11y spec §6): a real {@code
 * Nessy.harness(...)}, a scripted model, real tools, and a {@link TestObservationRegistry} handed
 * in through the one seam. Where {@code ObservationsTest} folds events by hand to pin the segment
 * rules, this file proves the wiring — that the executors reach the open segment across their own
 * virtual threads, that the model's own {@code Usage} lands on the {@code chat} span, and that the
 * approval wait closes through the REAL {@code DeliveryWorker} fold rather than a hand-published
 * fact.
 */
class ObservedTurnTest {

  /** Any term: nothing in these tests clips it. */
  private static final Duration TERM = Duration.ofDays(1);

  private static final AgentId SCOPE = AgentId.of("prod-eu");

  private final TestObservationRegistry registry = TestObservationRegistry.create();

  @AfterEach
  void tearDown() {
    HarnessTeardown.shutdownAllTracked();
  }

  private static ToolCall call(String id, String name) {
    return new ToolCall(id, name, JsonNodeFactory.instance.objectNode());
  }

  /** Every context the registry has seen, open or stopped. */
  private List<Observation.Context> contexts() {
    List<Observation.Context> captured = new ArrayList<>();
    assertThat(registry).hasHandledContextsThatSatisfy(captured::addAll);
    return captured;
  }

  private List<Observation.Context> named(String contextualName) {
    return contexts().stream()
        .filter(context -> contextualName.equals(context.getContextualName()))
        .toList();
  }

  private Observation.Context only(String contextualName) {
    List<Observation.Context> found = named(contextualName);
    assertThat(found).as("observations contextually named '%s'", contextualName).hasSize(1);
    return found.getFirst();
  }

  /**
   * A desk answer only SUBMITS the drain (continuum-adoption spec §7): the fold that closes the
   * wait runs on the harness's own scheduler thread, so this waits for the outcome to land on the
   * span rather than assuming it already has.
   */
  private void awaitOutcome(String contextualName, String outcomeKey, PumpedExecutor pump)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5000;
    while (KeyValue.NONE_VALUE.equals(
            only(contextualName).getLowCardinalityKeyValue(outcomeKey).getValue())
        && System.currentTimeMillis() < deadline) {
      pump.pumpUntilQuiet();
      Thread.sleep(20);
    }
  }

  /**
   * The memory spans James asked for on 2026-08-26 — named for semconv's own operation enum rather
   * than a minted {@code nessy.memory.*}: {@code search_memory} for a recall, {@code create_memory}
   * for a remember, both children of the open segment, both counting RECORDS and never bytes.
   */
  @Nested
  class MemoryOperations {

    @Test
    void a_turn_recalls_and_remembers_through_spans_named_by_semconv() {
      var pump = new PumpedExecutor();
      var model =
          new ScriptedModel(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("hello back"),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(5, 2, 0, 0)))));

      Harness<String> harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pump)
                      .observationRegistry(registry));
      HarnessTeardown.track(harness);

      harness.bind(SCOPE).tell("hello");
      pump.pumpUntilQuiet();

      List<Observation.Context> searches = named("search_memory");
      assertThat(searches).isNotEmpty();
      assertThat(searches)
          .allSatisfy(
              search -> {
                assertThat(search.getName()).isEqualTo("search_memory");
                assertThat(search.getLowCardinalityKeyValue("gen_ai.operation.name").getValue())
                    .isEqualTo("search_memory");
                assertThat(search.getLowCardinalityKeyValue("gen_ai.agent.name").getValue())
                    .isEqualTo("agent");
              });
      // EXACTLY one record, not merely "not negative" (which a byte count, or an
      // always-zero implementation, would also satisfy): one tell, so the recall that
      // feeds the single model call sees exactly the one user message.
      assertThat(searches)
          .singleElement()
          .extracting(
              search -> search.getHighCardinalityKeyValue("gen_ai.memory.record.count").getValue())
          .isEqualTo("1");

      List<Observation.Context> creates = named("create_memory");
      assertThat(creates).isNotEmpty();
      assertThat(creates)
          .allSatisfy(
              create -> {
                assertThat(create.getName()).isEqualTo("create_memory");
                assertThat(create.getLowCardinalityKeyValue("gen_ai.operation.name").getValue())
                    .isEqualTo("create_memory");
                assertThat(
                        create.getHighCardinalityKeyValue("gen_ai.memory.record.count").getValue())
                    .isEqualTo("1");
              });
    }

    /**
     * The count is MESSAGES, and it grows exactly as the conversation does. A tool-using turn
     * recalls twice: once with the user's message alone, and once after the assistant's tool_use
     * and its result have been remembered. Pinning the exact progression is what a byte count, a
     * constant, or an always-zero implementation cannot fake.
     */
    @Test
    void the_recalled_record_count_grows_with_the_conversation() {
      var pump = new PumpedExecutor();
      var restart = call("c1", "restart");
      var model =
          new ScriptedModel(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(restart, null),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, new Usage(10, 2, 0, 0))),
                  List.of(
                      new ModelEvent.TextChunk("done"),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(20, 3, 0, 0)))));

      Harness<String> harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pump)
                      .observationRegistry(registry)
                      .tools(new EchoTool("restart")));
      HarnessTeardown.track(harness);

      harness.bind(SCOPE).tell("restart prod-eu");
      pump.pumpUntilQuiet();

      assertThat(named("search_memory"))
          .hasSize(2)
          .extracting(
              search -> search.getHighCardinalityKeyValue("gen_ai.memory.record.count").getValue())
          .containsExactly("1", "3");
    }

    /** Same containment as every other span: a memory span is a child of the open segment. */
    @Test
    void a_memory_span_is_parented_to_the_open_segment() {
      var pump = new PumpedExecutor();
      var model =
          new ScriptedModel(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("hello back"),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(5, 2, 0, 0)))));

      Harness<String> harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pump)
                      .observationRegistry(registry));
      HarnessTeardown.track(harness);

      harness.bind(SCOPE).tell("hello");
      pump.pumpUntilQuiet();

      Observation.Context segment = only("invoke_agent agent");
      List<Observation.Context> searches = named("search_memory");
      assertThat(searches).isNotEmpty();
      assertThat(searches)
          .allSatisfy(
              search -> {
                assertThat(search.getParentObservation()).isNotNull();
                assertThat(search.getParentObservation().getContextView()).isSameAs(segment);
              });
    }
  }

  @Nested
  class AToolUsingTurn {

    /**
     * §6's first case: one segment, one {@code chat} carrying the scripted {@code Usage}, two
     * {@code execute_tool}s, and every child parented to the segment. Two model turns run — the one
     * that asks for the tools, and the one that answers after them — so the {@code chat} count is
     * two while the SEGMENT count stays one: nothing parked, so nothing ended the segment early.
     */
    @Test
    void a_two_call_turn_yields_one_segment_with_its_chats_and_tools_beneath_it() {
      var pump = new PumpedExecutor();
      var restart = call("c1", "restart");
      var drain = call("c2", "drain");
      var model =
          new ScriptedModel(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(restart, null),
                      new ModelEvent.ToolUseEmitted(drain, null),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, new Usage(120, 34, 8, 0))),
                  List.of(
                      new ModelEvent.TextChunk("both done"),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(200, 12, 0, 0)))));

      Harness<String> harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pump)
                      .observationRegistry(registry)
                      .tools(new EchoTool("restart"), new EchoTool("drain")));
      HarnessTeardown.track(harness);

      harness.bind(SCOPE).tell("restart and drain prod-eu");
      pump.pumpUntilQuiet();

      assertThat(named("invoke_agent agent")).hasSize(1);
      Observation.Context segment = only("invoke_agent agent");
      assertThat(segment.getLowCardinalityKeyValue("nessy.turn.outcome").getValue())
          .isEqualTo("complete");

      List<Observation.Context> chats = named("chat scripted");
      assertThat(chats).hasSize(2);
      assertThat(chats)
          .allSatisfy(
              chat -> {
                assertThat(chat.getLowCardinalityKeyValue("gen_ai.operation.name").getValue())
                    .isEqualTo("chat");
                assertThat(chat.getLowCardinalityKeyValue("gen_ai.provider.name").getValue())
                    .isEqualTo("scripted");
                assertThat(chat.getLowCardinalityKeyValue("gen_ai.request.model").getValue())
                    .isEqualTo("scripted");
                assertThat(chat.getParentObservation()).isNotNull();
                assertThat(chat.getParentObservation().getContextView()).isSameAs(segment);
              });

      List<Observation.Context> tools =
          contexts().stream()
              .filter(
                  context ->
                      context.getContextualName() != null
                          && context.getContextualName().startsWith("execute_tool "))
              .toList();
      assertThat(tools).hasSize(2);
      assertThat(tools)
          .allSatisfy(
              tool -> {
                assertThat(tool.getLowCardinalityKeyValue("gen_ai.tool.type").getValue())
                    .isEqualTo("function");
                assertThat(tool.getParentObservation().getContextView()).isSameAs(segment);
              });
      assertThat(tools)
          .extracting(tool -> tool.getLowCardinalityKeyValue("gen_ai.tool.name").getValue())
          .containsExactlyInAnyOrder("restart", "drain");
      assertThat(tools)
          .extracting(tool -> tool.getHighCardinalityKeyValue("gen_ai.tool.call.id").getValue())
          .containsExactlyInAnyOrder("c1", "c2");
    }

    /**
     * §6: the chat span carries the vendor's own accounting. {@code TurnEnded}'s {@code Usage} used
     * to be discarded outright — "usage metrics ride the observability design, not this plan" — and
     * this is that design collecting it.
     */
    @Test
    void the_chat_span_carries_the_usage_the_model_reported() {
      var pump = new PumpedExecutor();
      var model =
          new ScriptedModel(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("hello back"),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(1234, 56, 78, 90)))));

      Harness<String> harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pump)
                      .observationRegistry(registry));
      HarnessTeardown.track(harness);

      harness.bind(SCOPE).tell("hello");
      pump.pumpUntilQuiet();

      Observation.Context chat = only("chat scripted");
      assertThat(chat.getHighCardinalityKeyValue("gen_ai.usage.input_tokens").getValue())
          .isEqualTo("1234");
      assertThat(chat.getHighCardinalityKeyValue("gen_ai.usage.output_tokens").getValue())
          .isEqualTo("56");
      assertThat(chat.getHighCardinalityKeyValue("gen_ai.usage.cache_read.input_tokens").getValue())
          .isEqualTo("78");
      assertThat(
              chat.getHighCardinalityKeyValue("gen_ai.usage.cache_write.input_tokens").getValue())
          .isEqualTo("90");
      assertThat(chat.getLowCardinalityKeyValue("gen_ai.response.finish_reasons").getValue())
          .isEqualTo("[end_turn]");
    }

    /**
     * The 2026-08-26 semconv audit: each of the three operations carries its OWN semconv duration
     * histogram as its Micrometer name, with the semconv span name riding as the contextual name.
     * Pinned end to end here so a drift between {@code Observations}' constants and the two
     * executors' private copies breaks the build.
     */
    @Test
    void each_operation_is_named_for_its_own_semconv_duration_histogram() {
      var pump = new PumpedExecutor();
      var model =
          new ScriptedModel(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(
                          new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode()), ""),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, new Usage(10, 2, 0, 0))),
                  List.of(
                      new ModelEvent.TextChunk("done"),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(20, 3, 0, 0)))));

      Harness<String> harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pump)
                      .observationRegistry(registry)
                      .tools(new EchoTool("restart")));
      HarnessTeardown.track(harness);

      harness.bind(SCOPE).tell("restart prod-eu");
      pump.pumpUntilQuiet();

      assertThat(only("invoke_agent agent").getName()).isEqualTo("gen_ai.invoke_agent.duration");
      assertThat(named("chat scripted"))
          .isNotEmpty()
          .allSatisfy(
              chat -> assertThat(chat.getName()).isEqualTo("gen_ai.client.operation.duration"));
      assertThat(only("execute_tool restart").getName()).isEqualTo("gen_ai.execute_tool.duration");
    }

    /**
     * The recommended and conditionally-required request attributes the 2026-08-26 audit added:
     * {@code gen_ai.request.stream} (conditionally required and always true here — the harness has
     * no non-streaming door), {@code gen_ai.request.max_tokens} from {@code ModelSettings}, and
     * {@code gen_ai.response.time_to_first_chunk}, measured to the first content event.
     */
    @Test
    void the_chat_span_carries_the_request_shape_and_the_time_to_first_chunk() {
      var pump = new PumpedExecutor();
      var model =
          new ScriptedModel(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("hello back"),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(1, 1, 0, 0)))));

      Harness<String> harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pump)
                      .observationRegistry(registry));
      HarnessTeardown.track(harness);

      harness.bind(SCOPE).tell("hello");
      pump.pumpUntilQuiet();

      Observation.Context chat = only("chat scripted");
      assertThat(chat.getLowCardinalityKeyValue("gen_ai.request.stream").getValue())
          .isEqualTo("true");
      assertThat(chat.getHighCardinalityKeyValue("gen_ai.request.max_tokens").getValue())
          .isEqualTo(Integer.toString(TestSettings.settings().maxTokens()));
      assertThat(
              Double.parseDouble(
                  chat.getHighCardinalityKeyValue("gen_ai.response.time_to_first_chunk")
                      .getValue()))
          .isNotNegative();
    }

    /** §6: a failed model call is still a measured call, and the failure is on the span. */
    @Test
    void a_failing_model_call_yields_a_chat_span_carrying_its_error_type() {
      var pump = new PumpedExecutor();
      var model = new ExplodingModel();

      Harness<String> harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pump)
                      .observationRegistry(registry));
      HarnessTeardown.track(harness);

      harness.bind(SCOPE).tell("hello");
      pump.pumpUntilQuiet();

      Observation.Context chat = only("chat exploding");
      assertThat(chat.getLowCardinalityKeyValue("error.type").getValue())
          .isEqualTo("IllegalStateException");
      assertThat(chat.getError()).isInstanceOf(IllegalStateException.class);

      // The turn itself ended, failed, and the segment says so.
      assertThat(
              only("invoke_agent agent").getLowCardinalityKeyValue("nessy.turn.outcome").getValue())
          .isEqualTo("failed");
    }
  }

  @Nested
  class AParkedApproval {

    /**
     * §6's hardest case, and the one §3.1's reform exists to make possible: the dwell span opens on
     * the fold that parks the call, survives the segment that opened it, and is closed by a fold
     * the REAL {@link org.jwcarman.nessy.agent.DeliveryWorker} performs when the desk's answer is
     * delivered. Nothing here publishes a fact by hand — the answer goes in at {@code
     * harness.approvals()} and comes out as a stopped span.
     */
    @Test
    void the_approval_wait_spans_the_park_and_closes_through_the_real_delivery_worker()
        throws InterruptedException {
      var pump = new PumpedExecutor();
      var restart = call("c1", "restart");
      var model =
          new ScriptedModel(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(restart, null),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, new Usage(10, 2, 0, 0))),
                  List.of(
                      new ModelEvent.TextChunk("restarted"),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(20, 3, 0, 0)))));

      Harness<String> harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pump)
                      .observationRegistry(registry)
                      .grants(ToolGrant.grant(new EchoTool("restart"), Approvers.defer())));
      HarnessTeardown.track(harness);

      harness.bind(SCOPE).tell("please restart");
      pump.pumpUntilQuiet();

      // Parked: the wait is open, the segment that parked it is closed, and the wait is its child.
      Observation.Context wait = only("nessy.approval.wait restart");
      assertThat(wait.getHighCardinalityKeyValue("gen_ai.tool.call.id").getValue()).isEqualTo("c1");
      assertThat(named("invoke_agent agent")).hasSize(1);
      assertThat(
              only("invoke_agent agent").getLowCardinalityKeyValue("nessy.turn.outcome").getValue())
          .isEqualTo("parked");
      assertThat(wait.getParentObservation().getContextView()).isSameAs(only("invoke_agent agent"));
      assertThat(wait.getLowCardinalityKeyValue("nessy.approval.answer").getValue())
          .isEqualTo(KeyValue.NONE_VALUE);
      assertThat(registry)
          .hasObservationWithNameEqualTo("nessy.approval.wait")
          .that()
          .isNotStopped();

      // The desk answers. approve() only submits the drain: the fold itself runs on the harness's
      // own scheduler thread, so this awaits the wait's closure rather than assuming it.
      harness.approvals().approve(SCOPE, "c1", "oncall", "go ahead");
      awaitOutcome("nessy.approval.wait restart", "nessy.approval.answer", pump);

      assertThat(
              only("nessy.approval.wait restart")
                  .getLowCardinalityKeyValue("nessy.approval.answer")
                  .getValue())
          .isEqualTo("approved");
      assertThat(registry)
          .hasObservationWithNameEqualTo("nessy.approval.wait")
          .that()
          .hasBeenStopped();
      // The delivery that resumed the scope opened a second segment (spec §2): a span never
      // straddles a park.
      assertThat(named("invoke_agent agent")).hasSize(2);
    }

    /**
     * §6: a deferring tool's {@code execute_tool} span ends when its BODY returns — that is the
     * execution — and the dwell that follows is {@code nessy.tool.wait}, closed at delivery.
     */
    @Test
    void a_deferring_tool_stops_at_return_and_its_wait_closes_at_delivery()
        throws InterruptedException {
      var pump = new PumpedExecutor();
      var slow = call("c1", "slow");
      var model =
          new ScriptedModel(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(slow, null),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, new Usage(10, 2, 0, 0))),
                  List.of(
                      new ModelEvent.TextChunk("finished"),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(20, 3, 0, 0)))));
      var tool = new DeferringTool();

      Harness<String> harness =
          Nessy.harness(
              h ->
                  h.model(model)
                      .systemPrompt(TestSettings.SYSTEM_PROMPT)
                      .settings(TestSettings.settings())
                      .executor(pump)
                      .observationRegistry(registry)
                      .tools(tool));
      HarnessTeardown.track(harness);

      harness.bind(SCOPE).tell("do the slow thing");
      pump.pumpUntilQuiet();

      Observation.Context execution = only("execute_tool slow");
      assertThat(execution.getLowCardinalityKeyValue("nessy.tool.deferred").getValue())
          .isEqualTo("true");
      assertThat(registry)
          .hasObservationWithNameEqualTo("gen_ai.execute_tool.duration")
          .that()
          .hasBeenStopped();
      assertThat(
              only("nessy.tool.wait slow")
                  .getLowCardinalityKeyValue("nessy.tool.outcome")
                  .getValue())
          .isEqualTo(KeyValue.NONE_VALUE);

      harness.completions().complete(tool.parked(), ToolResult.ok("finished"));
      awaitOutcome("nessy.tool.wait slow", "nessy.tool.outcome", pump);

      assertThat(
              only("nessy.tool.wait slow")
                  .getLowCardinalityKeyValue("nessy.tool.outcome")
                  .getValue())
          .isEqualTo("returned");
    }
  }

  /** A model whose stream throws before it ever yields an event. */
  static final class ExplodingModel implements Model {

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

  record NoInput() {}

  /** A tool that answers immediately, named at construction so one class serves several grants. */
  static final class EchoTool implements Tool<NoInput> {

    private final String name;
    final AtomicInteger invocations = new AtomicInteger();

    EchoTool(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String description() {
      return "echoes, for the observability tests";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      invocations.incrementAndGet();
      return Awaited.ready(ToolResult.ok(name + " done"));
    }
  }

  /** A tool that parks: its body returns as soon as it has recorded the wait. */
  static final class DeferringTool implements Tool<NoInput> {

    private volatile ComputationId parked;

    ComputationId parked() {
      return parked;
    }

    @Override
    public String name() {
      return "slow";
    }

    @Override
    public String description() {
      return "defers, for the observability tests";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      return Awaited.deferred((id, deadline) -> parked = id, TERM);
    }
  }
}
