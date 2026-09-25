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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.engine.schema.VictoolsInputSchemaGenerator;
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
import org.jwcarman.nessy.lease.LocalLocks;
import org.jwcarman.nessy.lease.Locks;
import org.jwcarman.nessy.lease.Locks.Attempt;
import org.jwcarman.nessy.spi.store.PayloadStore;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
  private static final InputSchemaGenerator SCHEMAS = new VictoolsInputSchemaGenerator();
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

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
        InferenceRequest request, org.jwcarman.nessy.inference.InferenceNarrator narrator) {
      seen.add(request);
      return answers.poll();
    }
  }

  private DefaultDirectHarness<String> harness(
      InferenceProvider model, Map<ToolName, DefaultDirectHarness.DirectTool> tools) {
    return new DefaultDirectHarness<>(
        new LocalLocks(),
        events,
        payloads,
        model,
        new SystemPrompt("You are terse."),
        InferenceOptions.of("a-model"),
        (Function<String, List<Block.ObservationContent>>) text -> List.of(new Block.Text(text)),
        tools,
        SCHEMAS,
        MAPPER);
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
            .ask(AgentId.random(), "what is the answer?");

    assertThat(outcome).isEqualTo(new Outcome.Answered<>("forty two"));
  }

  @Test
  @DisplayName("a turn that calls a tool runs the whole loop")
  void a_turn_with_a_tool() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("charge 42.00"));

    Outcome outcome =
        harness(model, Map.of(LOOKUP, tool("found it"))).ask(agent, "look up my charge");

    System.out.println("EVENTS: " + events.readFrom(agent, org.jwcarman.nessy.inference.Seq.NONE));
    assertThat(outcome).isEqualTo(new Outcome.Answered<>("charge 42.00"));
    assertThat(events.readFrom(agent, org.jwcarman.nessy.inference.Seq.NONE))
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
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("the answer itself"));

    harness(model, Map.of(LOOKUP, tool("the tool's own words")))
        .ask(agent, "a question with words");

    assertThat(events.readFrom(agent, org.jwcarman.nessy.inference.Seq.NONE).toString())
        .doesNotContain("a question with words")
        .doesNotContain("the tool's own words")
        .doesNotContain("the answer itself");
  }

  @Test
  @DisplayName("what the model is shown is rebuilt from the stream, not remembered")
  void the_transcript_is_projected_from_events() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("done"));

    harness(model, Map.of(LOOKUP, tool("found it"))).ask(agent, "look it up");

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
  @DisplayName("a second ask on the same agent sees the first turn as history")
  void a_scope_remembers() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(answering("first")).then(answering("second"));
    DefaultDirectHarness<String> harness = harness(model, Map.of());

    harness.ask(agent, "one");
    harness.ask(agent, "two");

    assertThat(model.seen.get(1).context().turns())
        .as("the second call was shown the first turn as well as its own")
        .hasSize(2);
  }

  @Test
  @DisplayName("a terminated agent refuses further work, loudly")
  void terminate_ends_it() {
    AgentId agent = AgentId.random();
    DefaultDirectHarness<String> harness = harness(new Scripted().then(answering("ok")), Map.of());
    harness.ask(agent, "hello");

    harness.terminate(agent);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> harness.ask(agent, "again"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("accepts nothing further");
  }

  @Test
  @DisplayName("a failing tool is reported to the model rather than ending the turn")
  void a_failing_tool_is_reported() {
    AgentId agent = AgentId.random();
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

    Outcome<String> outcome = harness(model, Map.of(LOOKUP, broken)).ask(agent, "try");

    assertThat(outcome).isEqualTo(new Outcome.Answered<>("sorry"));
    assertThat(events.readFrom(agent, org.jwcarman.nessy.inference.Seq.NONE))
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

  @Test
  @DisplayName("a second caller on a busy agent is told so, and the agent is untouched")
  void a_busy_scope_is_refused() {
    // Refusing every lock is what a held agent looks like from the outside, without needing a
    // second thread to hold one.
    Locks held =
        new Locks() {
          @Override
          public <T> Attempt<T> tryWithLock(String key, Supplier<T> work) {
            return new Attempt.Ignored<>();
          }
        };
    DefaultDirectHarness<String> harness =
        new DefaultDirectHarness<>(
            held,
            events,
            payloads,
            new Scripted().then(answering("never asked")),
            new SystemPrompt("You are terse."),
            InferenceOptions.of("a-model"),
            (Function<String, List<Block.ObservationContent>>)
                text -> List.of(new Block.Text(text)),
            Map.of(),
            SCHEMAS,
            MAPPER);

    AgentId agent = AgentId.random();

    Outcome<String> outcome = harness.ask(agent, "anyone home?");

    assertThat(outcome).isEqualTo(new Outcome.Busy<>());
    assertThat(events.readFrom(agent, org.jwcarman.nessy.inference.Seq.NONE))
        .as("nothing was appended, so nothing has to be undone")
        .isEmpty();
  }

  @Test
  @DisplayName("while one caller is mid-turn, the others are told the agent is busy")
  void only_one_of_many_callers_runs() throws Exception {
    AgentId agent = AgentId.random();
    CountDownLatch inTurn = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    // A model that does not return until it is let go, so the first caller is demonstrably still
    // holding the agent while the others ask. Without this the turn finishes first and the lock
    // serialises them instead of refusing, which proves nothing.
    InferenceProvider slow =
        (request, narrator) -> {
          inTurn.countDown();
          try {
            release.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          return answering("hi");
        };
    DefaultDirectHarness<String> harness = harness(slow, Map.of());

    try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Outcome<String>> holder = callers.submit(() -> harness.ask(agent, "hello"));
      inTurn.await();

      List<Outcome<String>> refused =
          IntStream.range(0, 7).mapToObj(_ -> harness.ask(agent, "hello")).toList();

      assertThat(refused).as("every one of them, at once").containsOnly(new Outcome.Busy<>());
      release.countDown();
      assertThat(holder.get()).isEqualTo(new Outcome.Answered<>("hi"));
    }
  }

  /** What a caller asks for when it wants data back rather than prose. */
  record Capital(String city, String country) {}

  @Test
  @DisplayName("asking for a shape sends the schema and hands back the shape, not the JSON")
  void an_answer_can_be_asked_for_in_a_shape() {
    Scripted model = new Scripted().then(answering("{\"city\":\"Paris\",\"country\":\"France\"}"));

    Outcome<Capital> outcome =
        harness(model, Map.of()).ask(AgentId.random(), "capital of France?", Capital.class);

    assertThat(outcome).isEqualTo(new Outcome.Answered<>(new Capital("Paris", "France")));
    assertThat(model.seen).hasSize(1);
    assertThat(model.seen.getFirst().outputSchema())
        .as("the provider was told the shape, which is the whole point")
        .isPresent();
    assertThat(model.seen.getFirst().outputSchema().orElseThrow().json())
        .contains("city")
        .contains("country");
  }

  @Test
  @DisplayName("asking for nothing in particular sends no schema at all")
  void prose_is_asked_for_without_a_shape() {
    Scripted model = new Scripted().then(answering("Paris."));

    Outcome<String> outcome = harness(model, Map.of()).ask(AgentId.random(), "capital?");

    assertThat(outcome).isEqualTo(new Outcome.Answered<>("Paris."));
    assertThat(model.seen.getFirst().outputSchema()).isEmpty();
  }

  @Test
  @DisplayName("an answer that will not fit the shape fails the turn rather than being handed back")
  void an_answer_that_misses_the_shape_fails() {
    Scripted model = new Scripted().then(answering("Paris, obviously."));

    Outcome<Capital> outcome =
        harness(model, Map.of()).ask(AgentId.random(), "capital of France?", Capital.class);

    assertThat(outcome)
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(Outcome.Failed.class))
        .extracting(Outcome.Failed::reason)
        .asString()
        .contains("did not fit");
  }

  @Test
  @DisplayName("a TypeRef carries type arguments a Class cannot")
  void a_shape_with_type_arguments_is_parsed_whole() {
    Scripted model =
        new Scripted().then(answering("[{\"city\":\"Paris\",\"country\":\"France\"}]"));

    Outcome<List<Capital>> outcome =
        harness(model, Map.of())
            .ask(AgentId.random(), "capitals?", new TypeRef<List<Capital>>() {});

    assertThat(outcome).isEqualTo(new Outcome.Answered<>(List.of(new Capital("Paris", "France"))));
  }
}
