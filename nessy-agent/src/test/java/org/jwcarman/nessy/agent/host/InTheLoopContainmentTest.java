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
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.spi.model.ModelEvent;

/**
 * Containment for the two spans this round adds (in-the-loop amendment §5). An {@code
 * ObservationHandler} is arbitrary application code running inline on whichever thread started,
 * scoped, or stopped a span; the rule the previous round established — telemetry describes the
 * work, it never participates in it — now has two more spans and two more callbacks (scope opened,
 * scope closed) to hold.
 *
 * <p>The stakes are higher than they were. A throw out of {@code nessy.fold}'s instrumentation
 * would abort a fold: the turn would not commit, the tool's result would be lost, and a CAS retry
 * loop would give up on the very contention it exists to converge past. So every case below runs a
 * REAL turn — model, approver, tool, fold — with a handler that explodes, and asserts that the turn
 * produced its real answer anyway.
 */
class InTheLoopContainmentTest {

  private static final AgentId SCOPE = AgentId.of("prod-eu");

  private final TestObservationRegistry registry = TestObservationRegistry.create();

  @AfterEach
  void tearDown() {
    HarnessTeardown.shutdownAllTracked();
  }

  /** Explodes on every callback an application handler has, for observations of one name. */
  private record ExplodesOn(String name) implements ObservationHandler<Observation.Context> {

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }

    @Override
    public void onStart(Observation.Context context) {
      explodeFor(context);
    }

    @Override
    public void onStop(Observation.Context context) {
      explodeFor(context);
    }

    @Override
    public void onScopeOpened(Observation.Context context) {
      explodeFor(context);
    }

    @Override
    public void onScopeClosed(Observation.Context context) {
      explodeFor(context);
    }

    private void explodeFor(Observation.Context context) {
      if (name.equals(context.getName())) {
        throw new IllegalStateException("this handler explodes around " + name);
      }
    }
  }

  /** Explodes on start only — the half that leaves the following stop with a NOOP to work on. */
  private record ExplodesOnStart(String name) implements ObservationHandler<Observation.Context> {

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }

    @Override
    public void onStart(Observation.Context context) {
      if (name.equals(context.getName())) {
        throw new IllegalStateException("this handler explodes starting " + name);
      }
    }
  }

  /** Explodes on stop only. */
  private record ExplodesOnStop(String name) implements ObservationHandler<Observation.Context> {

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }

    @Override
    public void onStop(Observation.Context context) {
      if (name.equals(context.getName())) {
        throw new IllegalStateException("this handler explodes stopping " + name);
      }
    }
  }

  /** What one whole turn produced, whatever the handlers did while it ran. */
  private record TurnResult(List<TurnEvent> events, int toolInvocations) {

    String reply() {
      return events.stream()
          .filter(TurnEvent.AssistantSaid.class::isInstance)
          .map(TurnEvent.AssistantSaid.class::cast)
          .flatMap(said -> said.message().content().stream())
          .filter(TextBlock.class::isInstance)
          .map(block -> ((TextBlock) block).text())
          .reduce("", String::concat);
    }

    List<ToolResult> results() {
      return events.stream()
          .filter(TurnEvent.ToolCallCompleted.class::isInstance)
          .map(event -> ((TurnEvent.ToolCallCompleted) event).result())
          .toList();
    }
  }

  /**
   * One real, gated, tool-using turn, driven inline so it is complete when {@code tell} returns.
   */
  private TurnResult turnWith(ObservationHandler<Observation.Context> handler) {
    registry.observationConfig().observationHandler(handler);
    var restart = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    var model =
        new ScriptedModel(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(restart, null),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE, new Usage(10, 2, 0, 0))),
                List.of(
                    new ModelEvent.TextChunk("restarted"),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(20, 3, 0, 0)))));
    var tool = new EchoTool();
    Approver approver = context -> new ApprovalOutcome.Answered(Approval.approved());

    Harness<String> harness =
        Nessy.harness(
            h ->
                h.model(model)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .executor(Runnable::run)
                    .observationRegistry(registry)
                    .grants(ToolGrant.grant(tool, approver)));
    HarnessTeardown.track(harness);

    List<TurnEvent> events = new ArrayList<>();
    var agent = harness.bind(SCOPE);
    try (var subscription = agent.subscribe(events::add)) {
      agent.tell("please restart");
    }
    return new TurnResult(events, tool.invocations.get());
  }

  private void assertTurnUnaffected(TurnResult result) {
    assertThat(result.reply()).isEqualTo("restarted");
    assertThat(result.toolInvocations()).isEqualTo(1);
    assertThat(result.results()).isNotEmpty();
    assertThat(result.results()).allSatisfy(r -> assertThat(r.isError()).isFalse());
  }

  @Nested
  class TheFoldSpan {

    @Test
    void a_handler_that_throws_starting_a_fold_leaves_the_turn_unaffected() {
      assertTurnUnaffected(turnWith(new ExplodesOnStart("nessy.fold")));
    }

    @Test
    void a_handler_that_throws_stopping_a_fold_leaves_the_turn_unaffected() {
      assertTurnUnaffected(turnWith(new ExplodesOnStop("nessy.fold")));
    }

    @Test
    void a_handler_that_throws_on_every_fold_callback_leaves_the_turn_unaffected() {
      assertTurnUnaffected(turnWith(new ExplodesOn("nessy.fold")));
    }
  }

  @Nested
  class TheApprovalSeekSpan {

    @Test
    void a_handler_that_throws_starting_the_ask_leaves_the_tool_result_unaffected() {
      assertTurnUnaffected(turnWith(new ExplodesOnStart("nessy.approval.seek")));
    }

    @Test
    void a_handler_that_throws_stopping_the_ask_leaves_the_tool_result_unaffected() {
      assertTurnUnaffected(turnWith(new ExplodesOnStop("nessy.approval.seek")));
    }

    @Test
    void a_handler_that_throws_on_every_ask_callback_leaves_the_tool_result_unaffected() {
      assertTurnUnaffected(turnWith(new ExplodesOn("nessy.approval.seek")));
    }
  }

  @Nested
  class TheSpansThatGainedAScope {

    @Test
    void a_handler_that_throws_around_execute_tool_leaves_the_turn_unaffected() {
      assertTurnUnaffected(turnWith(new ExplodesOn("gen_ai.execute_tool.duration")));
    }

    @Test
    void a_handler_that_throws_around_chat_leaves_the_turn_unaffected() {
      assertTurnUnaffected(turnWith(new ExplodesOn("gen_ai.client.operation.duration")));
    }

    @Test
    void a_handler_that_throws_around_a_memory_span_leaves_the_turn_unaffected() {
      assertTurnUnaffected(turnWith(new ExplodesOn("create_memory")));
    }

    @Test
    void a_handler_that_throws_around_a_recall_leaves_the_turn_unaffected() {
      assertTurnUnaffected(turnWith(new ExplodesOn("search_memory")));
    }
  }

  record NoInput() {}

  static final class EchoTool implements Tool<NoInput> {

    final AtomicInteger invocations = new AtomicInteger();

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
      invocations.incrementAndGet();
      return Awaited.ready(ToolResult.ok("restarted"));
    }
  }
}
