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
package org.jwcarman.nessy.engine.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;

/**
 * A whole turn, on one thread, with no database and nothing written down.
 *
 * <p>If these pass, the claim the design rests on is demonstrated rather than argued: the same
 * state machine that a durable harness drives across a transaction, an outbox and a poller also
 * runs inside a {@code while} loop with a {@link java.util.HashMap} for storage.
 */
class InlineRunnerTest {

  private static final ToolName LOOKUP = new ToolName("lookup");
  private static final CallId CALL = new CallId("call-1");

  /** Answers straight away. */
  private static InlineRunner.Model answering(InlineRunner runner, String answer) {
    return _ -> new AgentCommand.InferenceOutcome.Answered(runner.claimCheck(answer));
  }

  @Test
  @DisplayName("a turn with no tools runs to an answer")
  void a_plain_turn_runs() {
    InlineRunner runner = new InlineRunner(_ -> null, Map.of(), _ -> true);
    InlineRunner.Model model = answering(runner, "forty two");

    InlineRunner.Ran ran = new InlineRunner(model, Map.of(), _ -> true).run("what is the answer?");

    assertThat(ran.state()).isInstanceOf(AgentState.Idle.class);
    assertThat(ran.events())
        .extracting(event -> event.getClass().getSimpleName())
        .containsExactly("TurnStarted", "InferenceAnswered");
  }

  @Test
  @DisplayName("a turn that calls a tool runs the whole loop and comes back with an answer")
  void a_turn_with_a_tool_runs() {
    Map<ToolName, UnaryOperator<Object>> tools = Map.of(LOOKUP, question -> "charge 42.00");

    InlineRunner[] holder = new InlineRunner[1];
    InlineRunner.Model model =
        events -> {
          boolean toolHasRun = events.stream().anyMatch(AgentEvent.ToolSucceeded.class::isInstance);
          return toolHasRun
              ? new AgentCommand.InferenceOutcome.Answered(holder[0].claimCheck("refunded 42.00"))
              : new AgentCommand.InferenceOutcome.RequestedActions(
                  holder[0].claimCheck("please look it up"),
                  List.of(new ActionRequest.ToolCall(CALL, LOOKUP)));
        };
    holder[0] = new InlineRunner(model, tools, _ -> true);

    InlineRunner.Ran ran = holder[0].run("refund my duplicate charge");

    assertThat(ran.events())
        .extracting(event -> event.getClass().getSimpleName())
        .containsExactly(
            "TurnStarted",
            "ActionsRequested",
            "ToolApproved",
            "ToolSucceeded",
            "InferenceAnswered");
    assertThat(ran.answer()).isEqualTo("refunded 42.00");
    assertThat(ran.state()).isInstanceOf(AgentState.Idle.class);
  }

  @Test
  @DisplayName("a denied call never runs the tool, and the model is asked again anyway")
  void a_denied_call_never_runs() {
    boolean[] toolRan = {false};
    Map<ToolName, UnaryOperator<Object>> tools =
        Map.of(
            LOOKUP,
            question -> {
              toolRan[0] = true;
              return "should not happen";
            });

    InlineRunner[] holder = new InlineRunner[1];
    InlineRunner.Model model =
        events -> {
          boolean asked = events.stream().anyMatch(AgentEvent.ActionsRequested.class::isInstance);
          return asked
              ? new AgentCommand.InferenceOutcome.Answered(holder[0].claimCheck("cannot help"))
              : new AgentCommand.InferenceOutcome.RequestedActions(
                  holder[0].claimCheck("please look it up"),
                  List.of(new ActionRequest.ToolCall(CALL, LOOKUP)));
        };
    holder[0] = new InlineRunner(model, tools, _ -> false);

    InlineRunner.Ran ran = holder[0].run("do something forbidden");

    assertThat(toolRan[0]).isFalse();
    assertThat(ran.events())
        .extracting(event -> event.getClass().getSimpleName())
        .containsExactly("TurnStarted", "ActionsRequested", "ToolDenied", "InferenceAnswered");
  }

  @Test
  @DisplayName("no payload is ever in an event: every one of them carries a reference")
  void the_events_carry_no_content() {
    InlineRunner[] holder = new InlineRunner[1];
    InlineRunner.Model model =
        events ->
            new AgentCommand.InferenceOutcome.Answered(holder[0].claimCheck("the answer itself"));
    holder[0] = new InlineRunner(model, Map.of(), _ -> true);

    InlineRunner.Ran ran = holder[0].run("a question with content in it");

    assertThat(ran.events().toString())
        .doesNotContain("a question with content in it")
        .doesNotContain("the answer itself");
    assertThat(ran.answer()).isEqualTo("the answer itself");
  }

  @Test
  @DisplayName("replaying what it recorded lands in the same state it ended in")
  void replay_agrees_with_the_run() {
    InlineRunner[] holder = new InlineRunner[1];
    InlineRunner.Model model =
        events -> new AgentCommand.InferenceOutcome.Answered(holder[0].claimCheck("done"));
    holder[0] = new InlineRunner(model, Map.of(), _ -> true);

    InlineRunner.Ran ran = holder[0].run("anything");

    AgentState replayed = AgentState.idle(org.jwcarman.nessy.api.Seq.NONE).applyAll(ran.events());

    assertThat(replayed).isEqualTo(ran.state());
  }

  @Test
  @DisplayName("a failing tool is reported to the model rather than ending the turn")
  void a_failing_tool_is_reported() {
    Map<ToolName, UnaryOperator<Object>> tools =
        Map.of(
            LOOKUP,
            question -> {
              throw new IllegalStateException("the ledger is down");
            });

    InlineRunner[] holder = new InlineRunner[1];
    InlineRunner.Model model =
        events -> {
          boolean failed = events.stream().anyMatch(AgentEvent.ToolFailed.class::isInstance);
          return failed
              ? new AgentCommand.InferenceOutcome.Answered(holder[0].claimCheck("sorry"))
              : new AgentCommand.InferenceOutcome.RequestedActions(
                  holder[0].claimCheck("look it up"),
                  List.of(new ActionRequest.ToolCall(CALL, LOOKUP)));
        };
    holder[0] = new InlineRunner(model, tools, _ -> true);

    InlineRunner.Ran ran = holder[0].run("please try");

    assertThat(ran.events())
        .extracting(event -> event.getClass().getSimpleName())
        .contains("ToolFailed");
    assertThat(ran.state()).isInstanceOf(AgentState.Idle.class);
  }

  /** Unused, but it keeps the import honest about what a POC leaves out. */
  private static Optional<String> notDeferrable() {
    return Optional.empty();
  }
}
