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
package org.jwcarman.nessy.engine.direct;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.ScopeId;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.SystemPrompt;
import org.jwcarman.nessy.inference.Usage;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.inference.tool.CallId;
import org.jwcarman.nessy.inference.tool.InputSchema;
import org.jwcarman.nessy.inference.tool.ToolName;
import org.jwcarman.nessy.spi.store.PayloadStore;

/**
 * A whole turn, on one thread, with a map for storage.
 *
 * <p>The provider is scripted so these run without a key or a network; what they prove is the
 * harness, not the model. The last two are the ones that matter: no content ever reaches the event
 * stream, and what the model is shown is rebuilt from that stream rather than remembered.
 */
class DefaultDirectHarnessTest {

  private static final ToolName LOOKUP = new ToolName("lookup");
  private static final CallId CALL = new CallId("call-1");

  private final InMemoryAgentEventStore events = new InMemoryAgentEventStore();
  private final InMemoryPayloads payloads = new InMemoryPayloads();

  /** Answers with whatever it is handed, in order, one per call. */
  private static final class Scripted implements InferenceProvider {
    private final Deque<InferenceResult> answers = new ArrayDeque<>();
    private final List<InferenceRequest> seen = new java.util.ArrayList<>();

    Scripted then(InferenceResult result) {
      answers.add(result);
      return this;
    }

    @Override
    public InferenceResult infer(
        InferenceRequest request, org.jwcarman.nessy.inference.WireNarrator narrator) {
      seen.add(request);
      return answers.poll();
    }
  }

  private DefaultDirectHarness<String> harness(
      Scripted model, Map<ToolName, DefaultDirectHarness.DirectTool> tools) {
    return new DefaultDirectHarness<>(
        events,
        payloads,
        model,
        new SystemPrompt("You are terse."),
        InferenceOptions.of("a-model"),
        (Function<String, List<Block.ObservationContent>>) text -> List.of(new Block.Text(text)),
        tools);
  }

  private static InferenceResult answering(String text) {
    return new InferenceResult.Answer(List.of(new Block.Text(text)), Usage.unknown());
  }

  private static InferenceResult asking(String tool) {
    return new InferenceResult.Actions(
        List.of(new Block.ToolCall(CALL, new ToolName(tool), "{\"id\":\"42\"}")), Usage.unknown());
  }

  private static DefaultDirectHarness.DirectTool tool(String result) {
    return new DefaultDirectHarness.DirectTool() {
      public String description() {
        return "looks things up";
      }

      public InputSchema schema() {
        return new InputSchema("{\"type\":\"object\"}");
      }

      public List<Block.ToolResultContent> call(String arguments) {
        return List.of(new Block.Text(result));
      }
    };
  }

  @Test
  @DisplayName("a turn with no tools runs to an answer")
  void a_plain_turn() {
    Outcome outcome =
        harness(new Scripted().then(answering("forty two")), Map.of())
            .ask(ScopeId.fresh(), "what is the answer?");

    assertThat(outcome).isEqualTo(new Outcome.Answered("forty two"));
  }

  @Test
  @DisplayName("a turn that calls a tool runs the whole loop")
  void a_turn_with_a_tool() {
    ScopeId scope = ScopeId.fresh();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("charge 42.00"));

    Outcome outcome =
        harness(model, Map.of(LOOKUP, tool("found it"))).ask(scope, "look up my charge");

    System.out.println("EVENTS: " + events.readFrom(scope, org.jwcarman.nessy.inference.Seq.NONE));
    assertThat(outcome).isEqualTo(new Outcome.Answered("charge 42.00"));
    assertThat(events.readFrom(scope, org.jwcarman.nessy.inference.Seq.NONE))
        .extracting(e -> e.getClass().getSimpleName())
        .containsExactly(
            "TurnStarted",
            "ActionsRequested",
            "ToolApproved",
            "ToolSucceeded",
            "InferenceAnswered");
  }

  @Test
  @DisplayName("no content ever reaches the event stream: every event carries a reference")
  void the_stream_holds_no_content() {
    ScopeId scope = ScopeId.fresh();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("the answer itself"));

    harness(model, Map.of(LOOKUP, tool("the tool's own words")))
        .ask(scope, "a question with words");

    assertThat(events.readFrom(scope, org.jwcarman.nessy.inference.Seq.NONE).toString())
        .doesNotContain("a question with words")
        .doesNotContain("the tool's own words")
        .doesNotContain("the answer itself");
  }

  @Test
  @DisplayName("what the model is shown is rebuilt from the stream, not remembered")
  void the_transcript_is_projected_from_events() {
    ScopeId scope = ScopeId.fresh();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("done"));

    harness(model, Map.of(LOOKUP, tool("found it"))).ask(scope, "look it up");

    // The second call saw a turn carrying the observation, the request and the tool's result --
    // all of it resolved back out of the claim check.
    InferenceRequest second = model.seen.get(1);
    assertThat(second.context().turns())
        .singleElement()
        .satisfies(
            turn -> {
              assertThat(turn.observation().blocks()).isNotEmpty();
              assertThat(turn.exchanges())
                  .singleElement()
                  .satisfies(
                      exchange -> {
                        assertThat(exchange.calls())
                            .singleElement()
                            .extracting(Block.ToolCall::name)
                            .isEqualTo(LOOKUP);
                        assertThat(exchange.outcomes())
                            .singleElement()
                            .isInstanceOf(
                                org.jwcarman.nessy.inference.turn.ToolOutcome.Succeeded.class);
                      });
            });
  }

  @Test
  @DisplayName("a second ask on the same scope sees the first turn as history")
  void a_scope_remembers() {
    ScopeId scope = ScopeId.fresh();
    Scripted model = new Scripted().then(answering("first")).then(answering("second"));
    DefaultDirectHarness<String> harness = harness(model, Map.of());

    harness.ask(scope, "one");
    harness.ask(scope, "two");

    assertThat(model.seen.get(1).context().turns())
        .as("the second call was shown the first turn as well as its own")
        .hasSize(2);
  }

  @Test
  @DisplayName("a terminated scope refuses further work, loudly")
  void terminate_ends_it() {
    ScopeId scope = ScopeId.fresh();
    DefaultDirectHarness<String> harness = harness(new Scripted().then(answering("ok")), Map.of());
    harness.ask(scope, "hello");

    harness.terminate(scope);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> harness.ask(scope, "again"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("accepts nothing further");
  }

  @Test
  @DisplayName("a failing tool is reported to the model rather than ending the turn")
  void a_failing_tool_is_reported() {
    ScopeId scope = ScopeId.fresh();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("sorry"));
    DefaultDirectHarness.DirectTool broken =
        new DefaultDirectHarness.DirectTool() {
          public String description() {
            return "breaks";
          }

          public InputSchema schema() {
            return new InputSchema("{\"type\":\"object\"}");
          }

          public List<Block.ToolResultContent> call(String arguments) {
            throw new IllegalStateException("the ledger is down");
          }
        };

    Outcome outcome = harness(model, Map.of(LOOKUP, broken)).ask(scope, "try");

    assertThat(outcome).isEqualTo(new Outcome.Answered("sorry"));
    assertThat(events.readFrom(scope, org.jwcarman.nessy.inference.Seq.NONE))
        .extracting(e -> e.getClass().getSimpleName())
        .contains("ToolFailed");
  }

  @Test
  @DisplayName("the payload store keeps a content array whole and in order")
  void content_arrays_survive_whole() {
    List<Block> array =
        List.of(
            new Block.Text("first"),
            new Block.Provider("anthropic", "{\"sig\":\"x\"}"),
            new Block.Text("last"));

    PayloadStore.Resolved back = payloads.get(payloads.put(array));

    assertThat(back)
        .asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.type(PayloadStore.Resolved.Found.class))
        .extracting(PayloadStore.Resolved.Found::content)
        .isEqualTo(array);
  }
}
