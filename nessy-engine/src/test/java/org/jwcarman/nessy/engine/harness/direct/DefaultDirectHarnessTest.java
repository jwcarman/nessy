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
package org.jwcarman.nessy.engine.harness.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ContextConfig;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.DirectHarnessFactory;
import org.jwcarman.nessy.api.InferenceConfig;
import org.jwcarman.nessy.api.JsonSchemaGenerator;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.OutputReader;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TerminationOutcome;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnStats;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.VendorProperty;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolConfig;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryLocks;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.chapter.Transcripts;
import org.jwcarman.nessy.engine.core.AgentCommand;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.engine.core.Decision;
import org.jwcarman.nessy.engine.narration.Heard;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A whole turn, on one thread, with a map for storage.
 *
 * <p>The provider is scripted so these run without a key or a network; what they prove is the
 * harness, not the model. The last two are the ones that matter: the event stream holds content
 * only as references and as the two bounded lines a tool call leaves, and what the model is shown
 * is rebuilt from that stream rather than remembered.
 */
class DefaultDirectHarnessTest {

  /** Any key: the tests here are not about which one a call gets. */
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));

  /** Stands in for a tally nobody is asserting on, and is never compared. */
  private static final TurnStats ANY_STATS = TurnStats.opened(Instant.EPOCH);

  private static final AgentType TYPE = new AgentType("chat");
  private static final int MAX_TAIL = 50;

  private static final ToolName LOOKUP = new ToolName("lookup");
  private static final CallId CALL = new CallId("call-1");
  private static final JsonSchemaGenerator SCHEMAS = new VictoolsJsonSchemaGenerator();
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /**
   * One clock for the store and for every harness built here.
   *
   * <p>The two were once different clocks -- the store on the system clock, a recovery test's
   * harness fixed a day into the future -- and that skew made every row in the store read as
   * overdue, including the rows recovery writes inside the step it is running. A test has to fail
   * because the deadline logic is wrong, not because two clocks disagree, so there is one clock and
   * a test advances it deliberately.
   */
  private final AdvanceableClock clock = new AdvanceableClock(Instant.now());

  private final InMemoryAgentEvents events =
      new InMemoryAgentEvents(new JacksonCodecFactory(JsonMapper.builder().build()));
  private final InMemoryPayloads payloads =
      new InMemoryPayloads(new JacksonCodecFactory(JsonMapper.builder().build()));

  /** A clock that stands still until a test moves it, so "overdue" is something a test states. */
  private static final class AdvanceableClock extends Clock {
    private Instant now;

    AdvanceableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException("the test's clock has one zone");
    }
  }

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

  private DirectHarnessFactory factoryFor(InferenceProvider model, Locks locks) {
    return DefaultDirectHarnessFactory.of(
        c ->
            c.backend(new FixedDirectBackend(locks, events, payloads))
                .provider(ProviderId.of("test"), model)
                .schemas(SCHEMAS)
                .mapper(MAPPER)
                .clock(clock));
  }

  private DirectHarness<String, String> harness(InferenceProvider model) {
    return harness(model, List.of(), Approver.allow(), new InMemoryLocks(), MAX_TAIL, List.of());
  }

  private DirectHarness<String, String> harness(InferenceProvider model, Tool<Lookup> tool) {
    return harness(
        model, List.of(tool), Approver.allow(), new InMemoryLocks(), MAX_TAIL, List.of());
  }

  private DirectHarness<String, String> harness(
      InferenceProvider model, Tool<Lookup> tool, Approver approver) {
    return harness(model, List.of(tool), approver, new InMemoryLocks(), MAX_TAIL, List.of());
  }

  private DirectHarness<String, String> harness(
      InferenceProvider model,
      List<Tool<Lookup>> tools,
      Approver approver,
      Locks locks,
      int maxTail,
      List<AmbientSource> ambient) {
    return factoryFor(model, locks)
        .<String>create(
            TYPE,
            c -> {
              c.systemPrompt("You are terse.")
                  .inputRenderer(said -> List.of(new Block.Text(said)))
                  .inference(
                      in ->
                          in.provider("test")
                              .model("a-model")
                              .context(
                                  ctx -> {
                                    ctx.maxTail(maxTail);
                                    ambient.forEach(ctx::ambient);
                                  }));
              tools.forEach(tool -> c.tool(tool, t -> t.approver(approver)));
            });
  }

  /** A harness whose tools the test binds itself, so it can say what each one is bound with. */
  private DirectHarness<String, String> harnessWithTools(
      InferenceProvider model, Customizer<DirectHarnessConfig<String>> tools) {
    return factoryFor(model, new InMemoryLocks())
        .<String>create(
            TYPE,
            c -> {
              c.systemPrompt("You are terse.")
                  .inputRenderer(said -> List.of(new Block.Text(said)))
                  .inference(
                      in ->
                          in.provider("test")
                              .model("a-model")
                              .context(ctx -> ctx.maxTail(MAX_TAIL)));
              tools.customize(c);
            });
  }

  /** A harness bound to a shape at creation -- what asking for a shape means now. */
  private <T> DirectHarness<String, T> shapedHarness(InferenceProvider model, Class<T> type) {
    return factoryFor(model, new InMemoryLocks())
        .<String, T>create(
            TYPE,
            type,
            c ->
                c.systemPrompt("You are terse.")
                    .inputRenderer(said -> List.of(new Block.Text(said)))
                    .inference(
                        in ->
                            in.provider("test")
                                .model("a-model")
                                .context(ctx -> ctx.maxTail(MAX_TAIL))));
  }

  /** A harness that reads its answer some way other than parsing it as JSON. */
  private <T> DirectHarness<String, T> shapedHarness(
      InferenceProvider model, Class<T> type, OutputReader<T> reader) {
    return factoryFor(model, new InMemoryLocks())
        .<String, T>create(
            TYPE,
            type,
            reader,
            c ->
                c.systemPrompt("You are terse.")
                    .inputRenderer(said -> List.of(new Block.Text(said)))
                    .inference(
                        in ->
                            in.provider("test")
                                .model("a-model")
                                .context(ctx -> ctx.maxTail(MAX_TAIL))));
  }

  /** The general form, for a shape a {@code Class} cannot carry -- a generic collection. */
  private <T> DirectHarness<String, T> shapedHarness(InferenceProvider model, TypeRef<T> type) {
    return factoryFor(model, new InMemoryLocks())
        .<String, T>create(
            TYPE,
            type,
            c ->
                c.systemPrompt("You are terse.")
                    .inputRenderer(said -> List.of(new Block.Text(said)))
                    .inference(
                        in ->
                            in.provider("test")
                                .model("a-model")
                                .context(ctx -> ctx.maxTail(MAX_TAIL))));
  }

  private static InferenceResult answering(String text) {
    return answering(text, Usage.unreported());
  }

  /** The same, from a vendor that counted, for the tests that are about what a turn cost. */
  private static InferenceResult answering(String text, Usage usage) {
    return new InferenceResult.Answer(List.of(new Block.Text(text)), usage);
  }

  private static InferenceResult asking(String tool) {
    return new InferenceResult.Actions(
        List.of(new Block.ToolCall(CALL, new ToolName(tool), "{\"id\":\"42\"}")),
        Usage.unreported());
  }

  record Lookup(String id) {}

  /** A tool that answers with one line. */
  private static Tool<Lookup> tool(String result) {
    return tool(LOOKUP, result);
  }

  /** A tool of the given name that answers with one line. */
  private static Tool<Lookup> tool(ToolName name, String result) {
    return new Tool<Lookup>() {
      @Override
      public Class<Lookup> inputType() {
        return Lookup.class;
      }

      @Override
      public ToolName name() {
        return name;
      }

      @Override
      public String description() {
        return "looks something up";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Lookup> request) {
        return Awaited.ready(ToolResult.ok(new Block.Text(result)));
      }
    };
  }

  /** A tool that returns a failure carrying the given message, which may be null. */
  private static Tool<Lookup> failing(String message) {
    return failing(message, new AtomicInteger());
  }

  /** The same, counting each time it is called. */
  private static Tool<Lookup> failing(String message, AtomicInteger calls) {
    return new Tool<Lookup>() {
      @Override
      public Class<Lookup> inputType() {
        return Lookup.class;
      }

      @Override
      public ToolName name() {
        return LOOKUP;
      }

      @Override
      public String description() {
        return "reports a failure";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Lookup> request) {
        calls.incrementAndGet();
        return Awaited.ready(new ToolResult.Failure(message));
      }
    };
  }

  /** A tool that throws, to prove a broken tool is told to the model rather than ending a turn. */
  private static Tool<Lookup> broken(String message) {
    return new Tool<Lookup>() {
      @Override
      public Class<Lookup> inputType() {
        return Lookup.class;
      }

      @Override
      public ToolName name() {
        return LOOKUP;
      }

      @Override
      public String description() {
        return "breaks";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Lookup> request) {
        throw new IllegalStateException(message);
      }
    };
  }

  @Test
  @DisplayName("the answer says what the turn cost, so a caller need not read the event store")
  void an_answer_carries_what_it_cost() {
    Outcome<String> outcome =
        harness(new Scripted().then(answering("forty two", Usage.of("a-model", 100, 20))))
            .ask(AgentId.random(), "what is the answer?");

    assertThat(outcome)
        .asInstanceOf(InstanceOfAssertFactories.type(Outcome.Answered.class))
        .extracting(Outcome.Answered::stats)
        .satisfies(
            stats -> {
              assertThat(stats.modelCalls()).isEqualTo(1);
              assertThat(stats.spent()).isEqualTo(Tokens.of(120));
              assertThat(stats.wasted()).as("nothing failed").isEqualTo(Tokens.none());
              assertThat(stats.productiveTokens()).isEqualTo(Tokens.of(120));
            });
  }

  @Test
  @DisplayName(
      "a story event is heard with the seq it was stored at and the time its step was written at")
  void a_story_event_is_heard_with_its_stored_position() {
    Heard heard = new Heard();
    AgentId agent = AgentId.random();
    DirectHarness<String, String> harness =
        factoryFor(new Scripted().then(answering("forty two")), new InMemoryLocks())
            .<String>create(
                TYPE,
                c ->
                    c.systemPrompt("You are terse.")
                        .inputRenderer(said -> List.of(new Block.Text(said)))
                        .inference(in -> in.provider("test").model("a-model"))
                        .listener(heard));

    harness.ask(agent, "what is the answer?");

    await().atMost(Duration.ofSeconds(10)).until(() -> heard.kindsFor(agent).contains("Answered"));
    List<Heard.Line> lines = heard.forAgent(agent).toList();
    assertThat(lines).extracting(Heard.Line::kind).containsExactly("TurnStarted", "Answered");
    List<AgentEvent> stored = events.readAll(TYPE, agent);
    assertThat(lines).allSatisfy(line -> assertThat(line.position()).isPresent());
    assertThat(lines.getFirst().position().orElseThrow().seq()).isEqualTo(stored.getFirst().seq());
    assertThat(lines.getLast().position().orElseThrow().seq()).isEqualTo(stored.getLast().seq());
    for (Heard.Line line : lines) {
      Narrated.Position position = line.position().orElseThrow();
      assertThat(position.at())
          .isEqualTo(clock.instant())
          .isEqualTo(events.writtenAt(TYPE, agent, position.seq()));
    }
  }

  @Test
  @DisplayName("a turn with no tools runs to an answer")
  void a_plain_turn() {
    Outcome<String> outcome =
        harness(new Scripted().then(answering("forty two")))
            .ask(AgentId.random(), "what is the answer?");

    assertThat(outcome)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("forty two", ANY_STATS));
  }

  @Test
  @DisplayName("a turn that calls a tool runs the whole loop")
  void a_turn_with_a_tool() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("charge 42.00"));

    Outcome outcome = harness(model, tool("found it")).ask(agent, "look up my charge");

    assertThat(outcome)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("charge 42.00", ANY_STATS));
    assertThat(events.readAll(TYPE, agent))
        .extracting(e -> e.getClass().getSimpleName())
        .containsExactly(
            "TurnStarted",
            "ActionsRequested",
            "ToolApproved",
            "ToolSucceeded",
            "InferenceAnswered");
  }

  @Test
  @DisplayName(
      "content stays out of the event stream, except the two lines a tool call leaves, each capped")
  void the_stream_holds_content_only_as_the_two_lines_a_tool_call_leaves() {
    AgentId agent = AgentId.random();
    String argumentText = "zebra-in-the-arguments";
    String resultText = "the tool's own words";
    InferenceResult asking =
        new InferenceResult.Actions(
            List.of(new Block.ToolCall(CALL, LOOKUP, "{\"id\":\"" + argumentText + "\"}")),
            Usage.unreported());
    Scripted model = new Scripted().then(asking).then(answering("the answer itself"));
    String expectedAction = String.valueOf(new Lookup(argumentText));

    harness(model, tool(resultText)).ask(agent, "a question with words");

    List<AgentEvent> stream = events.readAll(TYPE, agent);
    assertThat(stream.toString())
        .doesNotContain("a question with words")
        .doesNotContain("the answer itself")
        .containsOnlyOnce(argumentText)
        .containsOnlyOnce(resultText);

    // The call's arguments appear nowhere but the action line of the request that made the call.
    List<AgentEvent> beyondTheRequest =
        stream.stream().filter(e -> !(e instanceof AgentEvent.ActionsRequested)).toList();
    assertThat(beyondTheRequest).isNotEmpty();
    assertThat(beyondTheRequest.toString()).doesNotContain(argumentText);
    AgentEvent.ActionsRequested requested =
        stream.stream()
            .filter(AgentEvent.ActionsRequested.class::isInstance)
            .map(AgentEvent.ActionsRequested.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(requested.request().toString()).doesNotContain(argumentText);
    assertThat(requested.actions())
        .usingRecursiveFieldByFieldElementComparatorIgnoringFields("idempotencyKey")
        .containsExactly(new ActionRequest.ToolCall(CALL, LOOKUP, expectedAction, KEY));

    // The result appears nowhere but the line on the event that records it.
    List<AgentEvent> beyondTheResultLine =
        stream.stream().filter(e -> !(e instanceof AgentEvent.ToolSucceeded)).toList();
    assertThat(beyondTheResultLine).isNotEmpty();
    assertThat(beyondTheResultLine.toString()).doesNotContain(resultText);
    AgentEvent.ToolSucceeded succeeded =
        stream.stream()
            .filter(AgentEvent.ToolSucceeded.class::isInstance)
            .map(AgentEvent.ToolSucceeded.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(succeeded.rendered()).isEqualTo(resultText);

    assertThat(expectedAction.length()).isLessThanOrEqualTo(ToolConfig.LINE_CAP);
    assertThat(succeeded.rendered().length()).isLessThanOrEqualTo(ToolConfig.LINE_CAP);
  }

  @Test
  @DisplayName("a stringifier named on a tool is cut at the cap by the harness's own wiring")
  void the_harness_cuts_a_named_stringifier_at_the_cap() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("done"));

    harnessWithTools(
            model,
            c ->
                c.tool(
                    tool("anything"),
                    t -> t.action(x -> "a".repeat(3000)).result(x -> "r".repeat(3000))))
        .ask(agent, "look it up");

    List<AgentEvent> stream = events.readAll(TYPE, agent);
    assertThat(stream)
        .filteredOn(AgentEvent.ActionsRequested.class::isInstance)
        .map(AgentEvent.ActionsRequested.class::cast)
        .singleElement()
        .satisfies(
            requested ->
                assertThat(requested.actions())
                    .singleElement()
                    .asInstanceOf(InstanceOfAssertFactories.type(ActionRequest.ToolCall.class))
                    .extracting(ActionRequest.ToolCall::action)
                    .asString()
                    .hasSize(ToolConfig.LINE_CAP));
    assertThat(stream)
        .filteredOn(AgentEvent.ToolSucceeded.class::isInstance)
        .map(AgentEvent.ToolSucceeded.class::cast)
        .singleElement()
        .satisfies(succeeded -> assertThat(succeeded.rendered()).hasSize(ToolConfig.LINE_CAP));
  }

  @Test
  @DisplayName("a finished turn reads as one line per call, with each call's stored words")
  void a_gated_and_denied_call_and_a_call_that_ran_read_as_two_lines() {
    AgentId agent = AgentId.random();
    ToolName gated = new ToolName("gated");
    ToolName open = new ToolName("open");
    InferenceResult asking =
        new InferenceResult.Actions(
            List.of(
                new Block.ToolCall(new CallId("c-gated"), gated, "{\"id\":\"1\"}"),
                new Block.ToolCall(new CallId("c-open"), open, "{\"id\":\"2\"}")),
            Usage.unreported());
    Scripted model =
        new Scripted().then(asking).then(answering("done")).then(answering("done again"));
    DirectHarness<String, String> harness =
        harnessWithTools(
            model,
            c -> {
              c.tool(
                  tool(gated, "never"),
                  t ->
                      t.action(x -> "do " + x.id())
                          .approver(_ -> Awaited.ready(ApprovalResult.denied("not today"))));
              c.tool(tool(open, "found it"), t -> t.action(x -> "read " + x.id()));
            });

    harness.ask(agent, "first");
    harness.ask(agent, "second");

    List<Turn> history = model.seen.get(2).context().turns();
    assertThat(history).hasSizeGreaterThanOrEqualTo(1);
    assertThat(Transcripts.render(history.subList(0, 1)))
        .isEqualTo(
            """
            user: first
            assistant did: do 1 -- denied: not today
            assistant did: read 2 -- succeeded: found it
            assistant: done
            """);
  }

  @Test
  @DisplayName("a turn of several requests, one reusing an earlier call id, is answered in full")
  void answers_to_every_request_of_a_turn_are_taken_when_an_id_is_reused() {
    AgentId agent = AgentId.random();
    CallId first = new CallId("c-first");
    CallId second = new CallId("c-second");
    Scripted model =
        new Scripted()
            .then(asking(List.of(first, second)))
            .then(asking(List.of(first)))
            .then(answering("done"));

    Outcome<String> outcome = harness(model, tool("found it")).ask(agent, "look it up");

    assertThat(outcome)
        .asInstanceOf(InstanceOfAssertFactories.type(Outcome.Answered.class))
        .extracting(Outcome.Answered::value)
        .isEqualTo("done");
    List<AgentEvent> stream = events.readAll(TYPE, agent);
    assertThat(stream)
        .filteredOn(AgentEvent.ToolSucceeded.class::isInstance)
        .map(AgentEvent.ToolSucceeded.class::cast)
        .extracting(AgentEvent.ToolSucceeded::callId)
        .as("each request's calls were answered once, the reused id included")
        .containsExactlyInAnyOrder(first, second, first);
    assertThat(stream).last().isInstanceOf(AgentEvent.InferenceAnswered.class);
  }

  private static InferenceResult asking(List<CallId> ids) {
    return new InferenceResult.Actions(
        ids.stream()
            .<Block.ActionRequestContent>map(
                id -> new Block.ToolCall(id, LOOKUP, "{\"id\":\"42\"}"))
            .toList(),
        Usage.unreported());
  }

  @Test
  @DisplayName("what the model is shown is rebuilt from the stream, not remembered")
  void the_transcript_is_projected_from_events() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("done"));

    harness(model, tool("found it")).ask(agent, "look it up");

    // The second call saw a turn carrying the input, the request and the tool's result --
    // all of it resolved back out of the claim check.
    InferenceRequest second = model.seen.get(1);
    assertThat(second.context().turns())
        .singleElement()
        .satisfies(
            turn -> {
              assertThat(turn.input().blocks()).isNotEmpty();
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
                            .isInstanceOf(org.jwcarman.nessy.api.turn.ToolOutcome.Succeeded.class);
                      });
            });
  }

  @Test
  @DisplayName("a second ask on the same agent sees the first turn as history")
  void a_scope_remembers() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(answering("first")).then(answering("second"));
    DirectHarness<String, String> harness = harness(model);

    harness.ask(agent, "one");
    harness.ask(agent, "two");

    assertThat(model.seen.get(1).context().turns())
        .as("the second call was shown the first turn as well as its own")
        .hasSize(2);
  }

  /**
   * The core refuses a command sent to a dead agent by throwing, and should: that is a programming
   * error reaching the fold. A caller at this door is not a programming error, though -- they are
   * owed an answer, and an exception out of a request thread becomes somebody's 500. So the door
   * answers with the refusal rather than letting the fold's guard escape.
   */
  @Test
  @DisplayName("a terminated agent refuses further work, as an answer rather than an exception")
  void terminate_ends_it() {
    AgentId agent = AgentId.random();
    DirectHarness<String, String> harness = harness(new Scripted().then(answering("ok")));
    harness.ask(agent, "hello");

    assertThat(harness.terminate(agent)).isEqualTo(new TerminationOutcome.Ended());

    assertThat(harness.ask(agent, "again"))
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Refused<String>("terminated", ANY_STATS));
  }

  /** Asking twice is not an error, and the second answer says it was already over. */
  @Test
  void ending_an_ended_agent_says_so_rather_than_pretending_to_end_it_again() {
    AgentId agent = AgentId.random();
    DirectHarness<String, String> harness = harness(new Scripted().then(answering("ok")));
    harness.ask(agent, "hello");
    harness.terminate(agent);

    assertThat(harness.terminate(agent)).isEqualTo(new TerminationOutcome.AlreadyEnded());
  }

  /**
   * <b>The case that used to be silent.</b> Ending is accepted only from idle, so a request that
   * lands mid-turn writes nothing -- and while this returned void, a caller had no way to tell that
   * from having succeeded. The agent must still answer the turn it was already running.
   *
   * <p>Mid-turn is arranged by terminating from inside the tool the turn is waiting on: at that
   * moment the fold is holding an outstanding call, which is as busy as an agent gets.
   */
  @Test
  @DisplayName("ending an agent mid-turn is refused, and says so")
  void ending_a_busy_agent_reports_busy_rather_than_nothing() {
    AgentId agent = AgentId.random();
    AtomicReference<TerminationOutcome> whileRunning = new AtomicReference<>();
    AtomicReference<DirectHarness<String, String>> self = new AtomicReference<>();
    Tool<Lookup> terminatesItself =
        new Tool<>() {
          @Override
          public Class<Lookup> inputType() {
            return Lookup.class;
          }

          @Override
          public ToolName name() {
            return new ToolName("lookup");
          }

          @Override
          public String description() {
            return "ends its own agent while the turn is still open";
          }

          @Override
          public Awaited<ToolResult> call(ToolCallRequest<Lookup> request) {
            whileRunning.set(self.get().terminate(agent));
            return Awaited.ready(ToolResult.ok(new Block.Text("42")));
          }
        };
    DirectHarness<String, String> harness =
        harness(new Scripted().then(asking("lookup")).then(answering("ok")), terminatesItself);
    self.set(harness);

    Outcome<String> outcome = harness.ask(agent, "look it up");

    assertThat(whileRunning.get())
        .as("the turn was in flight, so ending it was refused")
        .isEqualTo(new TerminationOutcome.Busy());
    assertThat(outcome)
        .as("and the turn it was already running still finished")
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("ok", ANY_STATS));
  }

  @Test
  @DisplayName("a failing tool is reported to the model rather than ending the turn")
  void a_failing_tool_is_reported() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("sorry"));
    Outcome<String> outcome = harness(model, broken("the ledger is down")).ask(agent, "try");

    assertThat(outcome)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("sorry", ANY_STATS));
    assertThat(events.readAll(TYPE, agent))
        .extracting(e -> e.getClass().getSimpleName())
        .contains("ToolFailed");
  }

  @Test
  @DisplayName(
      "a tool that fails with a long message leaves at most the cap in its event and in what the"
          + " model reads")
  void
      a_tool_that_fails_with_a_long_message_leaves_at_most_the_cap_in_its_event_and_in_what_the_model_reads() {
    AgentId agent = AgentId.random();
    String message = "START" + "x".repeat(4_990) + "END";
    Scripted model = new Scripted().then(asking("lookup")).then(answering("sorry"));

    harness(model, failing(message)).ask(agent, "try");

    List<AgentEvent.ToolFailed> failures =
        events.readAll(TYPE, agent).stream()
            .filter(AgentEvent.ToolFailed.class::isInstance)
            .map(AgentEvent.ToolFailed.class::cast)
            .toList();
    assertThat(failures).singleElement().satisfies(f -> assertThat(f.message()).hasSize(1_000));
    String stored = failures.getFirst().message();
    assertThat(stored).startsWith("START").endsWith("END").contains("...");
    assertThat(model.seen.get(1).context().turns())
        .singleElement()
        .satisfies(
            turn ->
                assertThat(turn.exchanges())
                    .singleElement()
                    .satisfies(
                        exchange ->
                            assertThat(exchange.outcomes())
                                .singleElement()
                                .isEqualTo(
                                    new ToolOutcome.Failed(failures.getFirst().callId(), stored))));
  }

  @Test
  @DisplayName(
      "a tool that fails without a message is recorded as failed with a message that says so")
  void a_tool_that_fails_without_a_message_is_recorded_as_failed_with_a_message_that_says_so() {
    AgentId agent = AgentId.random();
    AtomicInteger runs = new AtomicInteger();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("sorry"));

    Outcome<String> outcome = harness(model, failing(null, runs)).ask(agent, "try");

    assertThat(runs).as("the tool ran once").hasValue(1);
    assertThat(events.readAll(TYPE, agent))
        .filteredOn(AgentEvent.ToolFailed.class::isInstance)
        .map(AgentEvent.ToolFailed.class::cast)
        .singleElement()
        .satisfies(f -> assertThat(f.message()).isEqualTo("the tool failed and gave no message"));
    assertThat(outcome)
        .as("the turn went on to the next inference")
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("sorry", ANY_STATS));
  }

  @Test
  @DisplayName("the payload store keeps a content array whole and in order")
  void content_arrays_survive_whole() {
    List<Block> array =
        List.of(
            new Block.Text("first"),
            new Block.Provider("anthropic", "{\"sig\":\"x\"}"),
            new Block.Text("last"));

    Payloads.Resolved back = payloads.get(payloads.put(array));

    assertThat(back)
        .asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.type(Payloads.Resolved.Found.class))
        .extracting(Payloads.Resolved.Found::content)
        .isEqualTo(array);
  }

  // Rewritten for design record 2026-09-25-locks-as-plumbing §3/§4: the door no longer refuses a
  // second caller by failing to acquire the lock -- it waits a few milliseconds for it and then
  // the phase decides. A Locks stub that always answers Ignored no longer describes anything this
  // door does (its own withLock would poll that stub forever), so this drives a genuine second
  // caller into a genuinely busy phase instead: the first caller's inference is left hanging on a
  // latch, and the second caller reconstitutes that in-flight turn and is told Busy by the phase
  // check, not by a lock refusal.
  @Test
  @DisplayName(
      "a second caller on a busy agent is told so, and the first caller's own turn is untouched")
  void a_busy_scope_is_refused() throws Exception {
    AgentId agent = AgentId.random();
    CountDownLatch inTurn = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    InferenceProvider slow =
        (request, narrator) -> {
          inTurn.countDown();
          try {
            release.await();
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }
          return answering("hi");
        };
    DirectHarness<String, String> harness = harness(slow);

    try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Outcome<String>> holder = callers.submit(() -> harness.ask(agent, "hello"));
      inTurn.await();

      Outcome<String> outcome = harness.ask(agent, "anyone home?");

      assertThat(outcome).isEqualTo(new Outcome.Busy<>());
      assertThat(events.readAll(TYPE, agent))
          .as(
              "the second, busy caller appended nothing; only the first caller's own turn is on"
                  + " the stream")
          .hasSize(1)
          .first()
          .isInstanceOf(AgentEvent.TurnStarted.class);

      release.countDown();
      assertThat(holder.get())
          .usingRecursiveComparison()
          .ignoringFields("stats")
          .isEqualTo(new Outcome.Answered<>("hi", ANY_STATS));
    }
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
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }
          return answering("hi");
        };
    DirectHarness<String, String> harness = harness(slow);

    try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Outcome<String>> holder = callers.submit(() -> harness.ask(agent, "hello"));
      inTurn.await();

      List<Outcome<String>> refused =
          IntStream.range(0, 7).mapToObj(_ -> harness.ask(agent, "hello")).toList();

      assertThat(refused).as("every one of them, at once").containsOnly(new Outcome.Busy<>());
      release.countDown();
      assertThat(holder.get())
          .usingRecursiveComparison()
          .ignoringFields("stats")
          .isEqualTo(new Outcome.Answered<>("hi", ANY_STATS));
    }
  }

  // ---- deadlines the direct door now enforces in-process (design record ---------------------
  // 2026-09-25-locks-as-plumbing, §4b-4c): each timeout is tens of milliseconds, never seconds,
  // because the timed wait is Future.get(timeout), which is real time no stepped Clock can shorten.

  /** A provider that hangs on its first call and answers plainly on any call after that. */
  private static final class HangsOnce implements InferenceProvider {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      if (calls.getAndIncrement() == 0) {
        awaitForever();
        throw new IllegalStateException("unreachable: interrupted before returning");
      }
      return answering("the second call was never made to wait");
    }
  }

  /** Blocks until interrupted, the way a hung socket read or a hung human both do. */
  private static void awaitForever() {
    try {
      new CountDownLatch(1).await();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  @DisplayName(
      "a provider that never answers fails the turn at InferenceConfig.timeout, and the agent is"
          + " idle afterwards")
  void a_provider_that_never_answers_fails_at_its_own_timeout() {
    AgentId agent = AgentId.random();
    HangsOnce model = new HangsOnce();
    DirectHarness<String, String> harness =
        DefaultDirectHarnessFactory.of(
                f ->
                    f.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                        .provider(ProviderId.of("test"), model)
                        .schemas(SCHEMAS)
                        .mapper(MAPPER)
                        .clock(clock))
            .<String>create(
                TYPE,
                c ->
                    c.systemPrompt("You are terse.")
                        .inputRenderer(said -> List.of(new Block.Text(said)))
                        .inference(
                            in ->
                                in.provider("test")
                                    .model("a-model")
                                    .timeout(Duration.ofMillis(50))
                                    // A generous policy, to prove the door does not consult it:
                                    // one call is made regardless of how many are allowed.
                                    .retryPolicy(
                                        new RetryPolicy.FixedDelay(
                                            5, Duration.ofMillis(1), Duration.ZERO))));

    Outcome<String> timedOut = harness.ask(agent, "are you there?");

    assertThat(timedOut)
        .asInstanceOf(InstanceOfAssertFactories.type(Outcome.Failed.class))
        .extracting(Outcome.Failed::reason)
        .asString()
        .contains("no answer within");
    assertThat(model.calls).as("no retry was attempted").hasValue(1);

    // Idle, not stuck: a second turn on the same agent starts and finishes normally.
    Outcome<String> next = harness.ask(agent, "still there?");
    assertThat(next)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("the second call was never made to wait", ANY_STATS));
  }

  /** A tool that hangs until interrupted, the way a hung downstream call does. */
  private static Tool<Lookup> hangingTool() {
    return new Tool<Lookup>() {
      @Override
      public Class<Lookup> inputType() {
        return Lookup.class;
      }

      @Override
      public ToolName name() {
        return LOOKUP;
      }

      @Override
      public String description() {
        return "never returns";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Lookup> request) {
        awaitForever();
        throw new IllegalStateException("unreachable: interrupted before returning");
      }
    };
  }

  @Test
  @DisplayName(
      "a tool that never returns fails at its own ToolConfig.timeout, and the turn carries on")
  void a_tool_that_never_returns_fails_at_its_own_timeout() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("noted, moving on"));
    DirectHarness<String, String> harness =
        DefaultDirectHarnessFactory.of(
                f ->
                    f.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                        .provider(ProviderId.of("test"), model)
                        .schemas(SCHEMAS)
                        .mapper(MAPPER)
                        .clock(clock))
            .<String>create(
                TYPE,
                c -> {
                  c.systemPrompt("You are terse.")
                      .inputRenderer(said -> List.of(new Block.Text(said)))
                      .inference(in -> in.provider("test").model("a-model"));
                  c.tool(
                      hangingTool(),
                      t -> t.timeout(Duration.ofMillis(50)).approver(Approver.allow()));
                });

    Outcome<String> outcome = harness.ask(agent, "look it up");

    assertThat(outcome)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("noted, moving on", ANY_STATS));
    List<AgentEvent> history = events.readAll(TYPE, agent);
    assertThat(history).isNotEmpty();
    assertThat(history)
        .extracting(e -> e.getClass().getSimpleName())
        .as("the call was discharged as failed rather than left outstanding")
        .contains("ToolFailed");
  }

  /** An approver that hangs until interrupted, the way a person who never answers does. */
  private static Approver hangingApprover() {
    return request -> {
      awaitForever();
      throw new IllegalStateException("unreachable: interrupted before returning");
    };
  }

  @Test
  @DisplayName(
      "a blocking approver that never answers fails the call at its own ApproverConfig.timeout")
  void a_blocking_approver_that_never_answers_fails_at_its_own_timeout() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("noted, moving on"));
    DirectHarness<String, String> harness =
        DefaultDirectHarnessFactory.of(
                f ->
                    f.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                        .provider(ProviderId.of("test"), model)
                        .schemas(SCHEMAS)
                        .mapper(MAPPER)
                        .clock(clock))
            .<String>create(
                TYPE,
                c -> {
                  c.systemPrompt("You are terse.")
                      .inputRenderer(said -> List.of(new Block.Text(said)))
                      .inference(in -> in.provider("test").model("a-model"));
                  c.tool(
                      tool("should never run"),
                      t -> t.approver(hangingApprover(), a -> a.timeout(Duration.ofMillis(50))));
                });

    Outcome<String> outcome = harness.ask(agent, "look it up");

    assertThat(outcome)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("noted, moving on", ANY_STATS));
    List<AgentEvent> history = events.readAll(TYPE, agent);
    assertThat(history).isNotEmpty();
    assertThat(history)
        .extracting(e -> e.getClass().getSimpleName())
        .as("nobody said no, but the call is still discharged as failed")
        .contains("ToolFailed");
  }

  // ---- lazy recovery, by deadline (design record 2026-09-25-locks-as-plumbing, §4d) -------------
  // A caller that reconstitutes a busy phase reads not just the phase but when it started, and if
  // the deadline that applies to it has already passed, discharges it and carries on rather than
  // waiting behind it forever. Every scenario below fabricates the abandoned phase directly on the
  // event store -- exactly what a dead process leaves behind, since nothing here ever ran the
  // harness's own in-process compensation (§4c) for it -- and then moves the one clock the store
  // and the door share past the fabricated rows, so "overdue" is a thing the test did rather than
  // an accident of two clocks, and nothing here waits a real second either.

  /** Long enough past anything fabricated below to be overdue, short enough to read as a wait. */
  private static final Duration AN_HOUR = Duration.ofHours(1);

  @Test
  @DisplayName(
      "an inference past its deadline is recovered by a second caller, and the first turn's own"
          + " belated completion is discarded by phase rather than handed to anyone")
  void an_overdue_inference_is_recovered_by_a_second_caller() {
    AgentId agent = AgentId.random();
    PayloadRef abandonedInput = payloads.forAgent(agent).put(List.of(new Block.Text("first")));
    events.append(
        TYPE,
        agent,
        List.of(
            new AgentEvent.TurnStarted(new Seq(1), new TurnId(1), abandonedInput, Instant.EPOCH)),
        Seq.NONE,
        clock.instant());

    Scripted model = new Scripted().then(answering("second turn's answer"));
    DirectHarness<String, String> harness =
        DefaultDirectHarnessFactory.of(
                f ->
                    f.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                        .provider(ProviderId.of("test"), model)
                        .schemas(SCHEMAS)
                        .mapper(MAPPER)
                        .clock(clock))
            .<String>create(
                TYPE,
                c ->
                    c.systemPrompt("You are terse.")
                        .inputRenderer(said -> List.of(new Block.Text(said)))
                        .inference(
                            in ->
                                in.provider("test")
                                    .model("a-model")
                                    .timeout(Duration.ofMillis(50))));

    clock.advance(AN_HOUR);

    Outcome<String> outcome = harness.ask(agent, "second turn, please");

    assertThat(outcome)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("second turn's answer", ANY_STATS));

    List<AgentEvent> stream = events.readAll(TYPE, agent);
    assertThat(stream)
        .extracting(e -> e.getClass().getSimpleName())
        .as("the abandoned first turn is failed by recovery before the second turn even starts")
        .containsExactly("TurnStarted", "InferenceFailed", "TurnStarted", "InferenceAnswered");

    // The dangerous moment, reconstructed: the first turn's own completion arriving while the
    // agent is busy on the SECOND turn -- a slow-but-alive original losing the race to the
    // recovery that closed it out (§4d). Folding the whole stream would leave the agent idle,
    // which ignores everything and proves nothing, so the state is rebuilt as far as the second
    // turn's opening event and no further.
    List<AgentEvent> upToTheSecondTurnOpening = stream.subList(0, 3);
    AgentState midSecondTurn = AgentState.idle(Seq.NONE).applyAll(upToTheSecondTurnOpening);
    assertThat(midSecondTurn)
        .as("busy on the second turn, which is the state a late answer would be written into")
        .isInstanceOf(AgentState.Inferring.class);

    Decision belated =
        midSecondTurn.execute(
            new AgentCommand.CompleteInference(
                new TurnId(1),
                new AgentCommand.InferenceOutcome.Answered(
                    payloads.forAgent(agent).put(List.of(new Block.Text("too late"))),
                    Usage.unreported())));

    assertThat(belated.events())
        .as(
            "a completion for a turn already closed is ignored rather than written down as the"
                + " turn now open, which is what would hand it to that turn's caller")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "an approval past its own deadline is discharged, the inference its discharge reopens is"
          + " discharged too, and the agent is idle before the recovering caller's own turn"
          + " starts")
  void an_overdue_approval_discharges_its_call_and_the_inference_it_reopens() {
    AgentId agent = AgentId.random();
    PayloadRef abandonedInput = payloads.forAgent(agent).put(List.of(new Block.Text("first")));
    PayloadRef abandonedRequest =
        payloads.forAgent(agent).put(List.of(new Block.ToolCall(CALL, LOOKUP, "{}")));
    events.append(
        TYPE,
        agent,
        List.of(
            new AgentEvent.TurnStarted(new Seq(1), new TurnId(1), abandonedInput, Instant.EPOCH),
            new AgentEvent.ActionsRequested(
                new Seq(2),
                new TurnId(1),
                abandonedRequest,
                List.of(new ActionRequest.ToolCall(CALL, LOOKUP, "lookup", KEY)),
                Usage.unreported())),
        Seq.NONE,
        clock.instant());

    Scripted model = new Scripted().then(answering("second turn's answer"));
    DirectHarness<String, String> harness =
        DefaultDirectHarnessFactory.of(
                f ->
                    f.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                        .provider(ProviderId.of("test"), model)
                        .schemas(SCHEMAS)
                        .mapper(MAPPER)
                        .clock(clock))
            .<String>create(
                TYPE,
                c -> {
                  c.systemPrompt("You are terse.")
                      .inputRenderer(said -> List.of(new Block.Text(said)))
                      .inference(
                          in ->
                              in.provider("test").model("a-model").timeout(Duration.ofMillis(50)));
                  c.tool(
                      tool("should never run"),
                      t -> t.approver(Approver.allow(), a -> a.timeout(Duration.ofMillis(50))));
                });
    // Only the fabricated approval is made overdue by this: the inference its discharge reopens
    // is written an instant later, on this same clock, and is inside its own deadline the moment
    // it appears. Recovery discharges it anyway, because nothing holds it.
    clock.advance(AN_HOUR);

    Outcome<String> outcome = harness.ask(agent, "second turn, please");

    assertThat(outcome)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("second turn's answer", ANY_STATS));
    // "The provider stub records zero calls" (design record, this step's brief) is true of
    // recovery itself: discharging the expired approval and the inference its discharge reopened
    // is pure fold and append, with no call to the provider. The one call recorded below is the
    // recovering caller's own live turn, not either discharge.
    assertThat(model.seen)
        .as("recovery made no calls; this is the recovering caller's own turn")
        .hasSize(1);

    List<AgentEvent> stream = events.readAll(TYPE, agent);
    assertThat(stream)
        .extracting(e -> e.getClass().getSimpleName())
        .as(
            "both the expired approval and the inference its discharge reopened are failed, and"
                + " the agent is idle, before the recovering caller's own turn starts")
        .containsExactly(
            "TurnStarted",
            "ActionsRequested",
            "ToolFailed",
            "InferenceFailed",
            "TurnStarted",
            "InferenceAnswered");
  }

  @Test
  @DisplayName(
      "its twin: a call inside its own deadline is left alone, and the caller is told" + " Busy")
  void a_call_within_its_deadline_is_left_alone() {
    AgentId agent = AgentId.random();
    PayloadRef abandonedInput = payloads.forAgent(agent).put(List.of(new Block.Text("first")));
    PayloadRef abandonedRequest =
        payloads.forAgent(agent).put(List.of(new Block.ToolCall(CALL, LOOKUP, "{}")));
    events.append(
        TYPE,
        agent,
        List.of(
            new AgentEvent.TurnStarted(new Seq(1), new TurnId(1), abandonedInput, Instant.EPOCH),
            new AgentEvent.ActionsRequested(
                new Seq(2),
                new TurnId(1),
                abandonedRequest,
                List.of(new ActionRequest.ToolCall(CALL, LOOKUP, "lookup", KEY)),
                Usage.unreported())),
        Seq.NONE,
        clock.instant());

    DirectHarness<String, String> harness =
        DefaultDirectHarnessFactory.of(
                f ->
                    f.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                        .provider(ProviderId.of("test"), new Scripted())
                        .schemas(SCHEMAS)
                        .mapper(MAPPER)
                        .clock(clock))
            .<String>create(
                TYPE,
                c -> {
                  c.systemPrompt("You are terse.")
                      .inputRenderer(said -> List.of(new Block.Text(said)))
                      .inference(
                          in -> in.provider("test").model("a-model").timeout(Duration.ofHours(1)));
                  c.tool(
                      tool("should never run"),
                      t -> t.approver(Approver.allow(), a -> a.timeout(Duration.ofHours(1))));
                });

    Outcome<String> outcome = harness.ask(agent, "anyone home?");

    assertThat(outcome).isEqualTo(new Outcome.Busy<>());
    assertThat(events.readAll(TYPE, agent))
        .as("nothing was discharged, and the busy caller appended nothing")
        .hasSize(2);
  }

  /**
   * The decline happens in {@code beginTurn}, before a turn is opened: this caller never reaches
   * the final read at all, which is why it is not a test of the turn filter. What it does prove is
   * that a busy agent is the phase check's answer even when an answer is sitting on the stream --
   * {@link #a_caller_is_handed_its_own_turns_answer_though_a_later_turn_has_ended_since} is the one
   * that pins the filter.
   */
  @Test
  @DisplayName("a caller arriving mid-turn is told Busy rather than shown the last answer")
  void a_mid_turn_caller_is_told_busy() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(answering("first turn's answer"));
    DirectHarness<String, String> harness =
        DefaultDirectHarnessFactory.of(
                f ->
                    f.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                        .provider(ProviderId.of("test"), model)
                        .schemas(SCHEMAS)
                        .mapper(MAPPER)
                        .clock(clock))
            .<String>create(
                TYPE,
                c ->
                    c.systemPrompt("You are terse.")
                        .inputRenderer(said -> List.of(new Block.Text(said)))
                        .inference(
                            in ->
                                in.provider("test").model("a-model").timeout(Duration.ofHours(1))));

    Outcome<String> first = harness.ask(agent, "first turn");
    assertThat(first)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("first turn's answer", ANY_STATS));

    // A second turn that a process started and never came back to finish -- well inside its own
    // deadline, so the caller below must be told Busy rather than shown whatever the fold has on
    // hand, which is the first turn's own answer.
    List<AgentEvent> afterFirstTurn = events.readAll(TYPE, agent);
    Seq lastSeq = afterFirstTurn.getLast().seq();
    Seq secondTurnSeq = lastSeq.next();
    events.append(
        TYPE,
        agent,
        List.of(
            new AgentEvent.TurnStarted(
                secondTurnSeq,
                secondTurnSeq.opensTurn(),
                payloads.forAgent(agent).put(List.of(new Block.Text("second"))),
                Instant.EPOCH)),
        lastSeq,
        clock.instant());

    Outcome<String> midTurn = harness.ask(agent, "are you still there?");

    assertThat(midTurn)
        .as("busy, not the first turn's answer handed back as though it were this caller's own")
        .isEqualTo(new Outcome.Busy<>())
        .isNotEqualTo(first);
  }

  /**
   * The door's final read is by turn, and this is what makes that scoping load-bearing.
   *
   * <p>No lock is held between a caller's last step and its read of what the turn came to, so
   * another caller's whole turn can start and finish in that window -- and its answer is then the
   * LAST terminal event on the stream. A read that took the latest one would hand this caller
   * somebody else's answer; the store below makes exactly that window happen, at the moment the
   * caller's own turn has ended and the door is about to look.
   *
   * <p>Chapters are off for this harness: the chapter keeper reads the stream on a thread of its
   * own once a turn ends, and an unrelated reader would spring the intrusion at a moment other than
   * the door's final read -- or two readers at once would both spring it.
   */
  @Test
  @DisplayName("a caller is handed its own turn's answer, though a later turn has ended since")
  void a_caller_is_handed_its_own_turns_answer_though_a_later_turn_has_ended_since() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(answering("this caller's own answer"));
    IntrudingEvents racing = new IntrudingEvents(events, payloads);
    DirectHarness<String, String> harness =
        DefaultDirectHarnessFactory.of(
                f ->
                    f.backend(new FixedDirectBackend(new InMemoryLocks(), racing, payloads))
                        .provider(ProviderId.of("test"), model)
                        .schemas(SCHEMAS)
                        .mapper(MAPPER)
                        .clock(clock))
            .<String>create(
                TYPE,
                c ->
                    c.systemPrompt("You are terse.")
                        .inputRenderer(said -> List.of(new Block.Text(said)))
                        .inference(
                            in ->
                                in.provider("test")
                                    .model("a-model")
                                    .context(ContextConfig::withoutChapters)));

    Outcome<String> outcome = harness.ask(agent, "mine, please");

    List<AgentEvent> stream = events.readAll(TYPE, agent);
    assertThat(stream)
        .extracting(e -> e.getClass().getSimpleName())
        .as("a whole later turn landed after this caller's own answer")
        .containsExactly("TurnStarted", "InferenceAnswered", "TurnStarted", "InferenceAnswered");
    assertThat(outcome)
        .as("its own turn's answer, not the latest one on the stream")
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("this caller's own answer", ANY_STATS));
  }

  /**
   * A store that slips somebody else's finished turn onto the stream the moment this caller's own
   * turn has ended -- the window between a caller's last locked step and its final read, which no
   * lock covers.
   */
  private static final class IntrudingEvents implements AgentEvents {

    private final AgentEvents delegate;
    private final Payloads payloads;
    private final AtomicBoolean intruded = new AtomicBoolean();

    IntrudingEvents(AgentEvents delegate, Payloads payloads) {
      this.delegate = delegate;
      this.payloads = payloads;
    }

    @Override
    public void append(
        AgentType type, AgentId agent, List<AgentEvent> events, Seq expectedLast, Instant at) {
      delegate.append(type, agent, events, expectedLast, at);
    }

    @Override
    public List<AgentEvent> readFrom(AgentType type, AgentId agent, Seq watermark) {
      intrude(type, agent);
      return delegate.readFrom(type, agent, watermark);
    }

    @Override
    public Stream<AgentEvent> streamFrom(AgentType type, AgentId agent, Seq watermark) {
      intrude(type, agent);
      return delegate.streamFrom(type, agent, watermark);
    }

    @Override
    public Stream<Written> streamWrittenFrom(AgentType type, AgentId agent, Seq after) {
      return delegate.streamWrittenFrom(type, agent, after);
    }

    @Override
    public List<AgentEvent> sinceLastTurnStarted(AgentType type, AgentId agent) {
      return delegate.sinceLastTurnStarted(type, agent);
    }

    @Override
    public Instant writtenAt(AgentType type, AgentId agent, Seq seq) {
      return delegate.writtenAt(type, agent, seq);
    }

    /**
     * Once, and only once this agent has an answered turn behind it: before that the reads are the
     * projection assembling the model's context, which is not the moment being described.
     */
    private void intrude(AgentType type, AgentId agent) {
      List<AgentEvent> stream = delegate.readAll(type, agent);
      if (stream.stream().noneMatch(AgentEvent.InferenceAnswered.class::isInstance)
          || !intruded.compareAndSet(false, true)) {
        return;
      }
      Seq last = stream.getLast().seq();
      Seq opening = last.next();
      delegate.append(
          type,
          agent,
          List.of(
              new AgentEvent.TurnStarted(
                  opening,
                  opening.opensTurn(),
                  payloads.forAgent(agent).put(List.of(new Block.Text("somebody else's input"))),
                  Instant.EPOCH),
              new AgentEvent.InferenceAnswered(
                  opening.next(),
                  opening.opensTurn(),
                  payloads.forAgent(agent).put(List.of(new Block.Text("somebody else's answer"))),
                  Usage.unreported())),
          last,
          Instant.EPOCH);
    }
  }

  @Test
  @DisplayName(
      "the inference retry policy is stored and read back, though this door does not" + " retry")
  void inference_retry_policy_is_stored_but_not_honoured() {
    DefaultDirectHarnessConfig<String> config =
        new DefaultDirectHarnessConfig<>(TYPE, ObservationRegistry.NOOP);
    RetryPolicy policy = new RetryPolicy.FixedDelay(3, Duration.ofSeconds(1), Duration.ZERO);

    config.inference(in -> in.retryPolicy(policy));

    assertThat(config.inference().retryPolicy()).isEqualTo(policy);
  }

  /** What a caller asks for when it wants data back rather than prose. */
  record Capital(String city, String country) {}

  @Test
  @DisplayName("asking for a shape sends the schema and hands back the shape, not the JSON")
  void an_answer_can_be_asked_for_in_a_shape() {
    Scripted model = new Scripted().then(answering("{\"city\":\"Paris\",\"country\":\"France\"}"));

    Outcome<Capital> outcome =
        shapedHarness(model, Capital.class).ask(AgentId.random(), "capital of France?");

    assertThat(outcome)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>(new Capital("Paris", "France"), ANY_STATS));
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

    Outcome<String> outcome = harness(model).ask(AgentId.random(), "capital?");

    assertThat(outcome)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("Paris.", ANY_STATS));
    assertThat(model.seen.getFirst().outputSchema()).isEmpty();
  }

  @Test
  @DisplayName("an answer that will not fit the shape fails the turn rather than being handed back")
  void an_answer_that_misses_the_shape_fails() {
    Scripted model = new Scripted().then(answering("Paris, obviously."));

    Outcome<Capital> outcome =
        shapedHarness(model, Capital.class).ask(AgentId.random(), "capital of France?");

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
        new Scripted().then(answering("{\"value\":[{\"city\":\"Paris\",\"country\":\"France\"}]}"));

    Outcome<List<Capital>> outcome =
        shapedHarness(model, new TypeRef<List<Capital>>() {}).ask(AgentId.random(), "capitals?");

    assertThat(outcome)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>(List.of(new Capital("Paris", "France")), ANY_STATS));
  }

  @Test
  @DisplayName("a list answer is asked for as an object whose value is the typed array")
  void a_list_answer_is_sent_wrapped_with_typed_items() {
    Scripted model =
        new Scripted().then(answering("{\"value\":[{\"city\":\"Paris\",\"country\":\"France\"}]}"));

    shapedHarness(model, new TypeRef<List<Capital>>() {}).ask(AgentId.random(), "capitals?");

    String sent = model.seen.getFirst().outputSchema().orElseThrow().json();
    assertThat(sent)
        .contains("\"required\":[\"value\"]")
        .contains("\"type\":\"array\"")
        .contains("city")
        .contains("country");
  }

  @Test
  @DisplayName("a record answer is sent as generated, not wrapped")
  void a_record_answer_is_not_wrapped() {
    Scripted model = new Scripted().then(answering("{\"city\":\"Paris\",\"country\":\"France\"}"));

    shapedHarness(model, Capital.class).ask(AgentId.random(), "capital?");

    assertThat(model.seen.getFirst().outputSchema().orElseThrow().json())
        .doesNotContain("\"value\"");
  }

  @Test
  @DisplayName("a text answer travels wrapped and comes back as the bare text")
  void a_string_answer_is_wrapped_and_unwrapped() {
    Scripted model = new Scripted().then(answering("{\"value\":\"Paris\"}"));

    Outcome<String> outcome =
        shapedHarness(model, new TypeRef<String>() {}).ask(AgentId.random(), "capital?");

    assertThat(outcome)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("Paris", ANY_STATS));
  }

  @Test
  @DisplayName("ambient background reaches the model, assembled the way the queued door does it")
  void ambient_is_shown_to_the_model() {
    Scripted model = new Scripted().then(answering("noted"));
    DirectHarness<String, String> harness =
        harness(
            model,
            List.of(),
            Approver.allow(),
            new InMemoryLocks(),
            MAX_TAIL,
            List.of(AmbientSource.constant(Ambient.text("notebook", "the deploy is frozen"))));

    harness.ask(AgentId.random(), "anything I should know?");

    assertThat(model.seen.getFirst().context().ambient())
        .singleElement()
        .extracting(Ambient::kind)
        .isEqualTo("notebook");
  }

  @Test
  @DisplayName("the tail window is honoured, so a long conversation does not send all of itself")
  void the_tail_is_windowed() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted();
    for (int i = 0; i < 5; i++) {
      model.then(answering("ok " + i));
    }
    DirectHarness<String, String> harness = harnessKeeping(model, 2);

    for (int i = 0; i < 5; i++) {
      harness.ask(agent, "question " + i);
    }

    // The fifth call is the one worth looking at: four turns are behind it, and only maxTail of
    // them may be sent -- the window is what makes a thousand-turn conversation affordable.
    assertThat(model.seen.getLast().context().tail()).hasSizeLessThanOrEqualTo(2);
  }

  private DirectHarness<String, String> harnessKeeping(Scripted model, int maxTail) {
    // A tail this short is only allowed where nothing is cut into chapters: a chapter could
    // otherwise be longer than the tail.
    return factoryFor(model, new InMemoryLocks())
        .<String>create(
            TYPE,
            c ->
                c.systemPrompt("You are terse.")
                    .inputRenderer(said -> List.of(new Block.Text(said)))
                    .inference(
                        in ->
                            in.provider("test")
                                .model("a-model")
                                .context(ctx -> ctx.withoutChapters().maxTail(maxTail))));
  }

  @Test
  @DisplayName("an approver that says no stops the tool, and the model is told")
  void a_denied_call_does_not_run() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("understood"));
    AtomicBoolean ran = new AtomicBoolean();
    Tool<Lookup> watched =
        new Tool<Lookup>() {
          @Override
          public Class<Lookup> inputType() {
            return Lookup.class;
          }

          @Override
          public ToolName name() {
            return LOOKUP;
          }

          @Override
          public String description() {
            return "looks something up";
          }

          @Override
          public Awaited<ToolResult> call(ToolCallRequest<Lookup> request) {
            ran.set(true);
            return Awaited.ready(ToolResult.ok(new Block.Text("should never happen")));
          }
        };

    Outcome<String> outcome =
        harness(model, watched, _ -> Awaited.ready(ApprovalResult.denied("not today")))
            .ask(agent, "look it up");

    assertThat(ran).as("a denied call is not a call").isFalse();
    assertThat(outcome)
        .usingRecursiveComparison()
        .ignoringFields("stats")
        .isEqualTo(new Outcome.Answered<>("understood", ANY_STATS));
    assertThat(events.readAll(TYPE, agent))
        .extracting(e -> e.getClass().getSimpleName())
        .contains("ToolDenied");
  }

  /**
   * The one thing that genuinely cannot cross to this door: an approver that answers later. There
   * is nowhere to put the waiting, so it discharges as a failure -- nobody said no, which is what
   * {@code ToolDenied} would claim -- rather than a turn that hangs.
   */
  @Test
  @DisplayName("an approver that defers is a failure, because nothing here can wait")
  void a_deferred_approval_is_failed() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted().then(asking("lookup")).then(answering("fine"));
    AtomicBoolean ran = new AtomicBoolean();
    Tool<Lookup> watched =
        new Tool<Lookup>() {
          @Override
          public Class<Lookup> inputType() {
            return Lookup.class;
          }

          @Override
          public ToolName name() {
            return LOOKUP;
          }

          @Override
          public String description() {
            return "looks something up";
          }

          @Override
          public Awaited<ToolResult> call(ToolCallRequest<Lookup> request) {
            ran.set(true);
            return Awaited.ready(ToolResult.ok(new Block.Text("should never happen")));
          }
        };

    harness(model, watched, _ -> Awaited.deferred()).ask(agent, "look it up");

    assertThat(ran).isFalse();
    assertThat(events.readAll(TYPE, agent))
        .extracting(e -> e.getClass().getSimpleName())
        .contains("ToolFailed");
  }

  @Test
  @DisplayName("a window of turns asks the payload store once, not once per block")
  void payloads_are_resolved_in_one_ask() {
    AgentId agent = AgentId.random();
    Scripted model = new Scripted();
    for (int i = 0; i < 4; i++) {
      model.then(answering("ok " + i));
    }
    CountingPayloads counting = new CountingPayloads(payloads);
    DirectHarness<String, String> harness =
        DefaultDirectHarnessFactory.of(
                f ->
                    f.backend(new FixedDirectBackend(new InMemoryLocks(), events, counting))
                        .provider(ProviderId.of("test"), model)
                        .schemas(SCHEMAS)
                        .mapper(MAPPER)
                        .clock(clock))
            .<String>create(
                TYPE,
                c ->
                    c.systemPrompt("You are terse.")
                        .inputRenderer(said -> List.of(new Block.Text(said)))
                        .inference(
                            in ->
                                in.provider("test")
                                    .model("a-model")
                                    .context(ContextConfig::withoutChapters)));

    for (int i = 0; i < 4; i++) {
      harness.ask(agent, "question " + i);
    }

    // Four turns behind the last call, each with an input and an answer. One ask per
    // projection is the point; one ask per block would grow with the conversation.
    assertThat(counting.batches).as("one batch per projection").isPositive();
    assertThat(counting.singles)
        .as("no payload resolved one at a time while building turns")
        .isLessThanOrEqualTo(counting.batches);
  }

  /** Counts how a projection reaches for its payloads. */
  private static final class CountingPayloads implements Payloads {
    private final Payloads delegate;
    private int batches;
    private int singles;

    CountingPayloads(Payloads delegate) {
      this.delegate = delegate;
    }

    @Override
    public PayloadRef put(List<? extends Block> content) {
      return delegate.put(content);
    }

    @Override
    public Resolved get(PayloadRef ref) {
      singles++;
      return delegate.get(ref);
    }

    @Override
    public java.util.Map<PayloadRef, Resolved> get(java.util.Collection<PayloadRef> refs) {
      batches++;
      java.util.Map<PayloadRef, Resolved> found = new java.util.LinkedHashMap<>();
      for (PayloadRef ref : refs) {
        found.computeIfAbsent(ref, delegate::get);
      }
      return found;
    }
  }

  @Nested
  @DisplayName("An answer read some way other than as JSON")
  class ACallersOwnReader {

    /**
     * <b>The reader supplied is the reader used.</b> A model that honours the shape while spelling
     * it differently is the whole reason this overload exists, so the answer here is deliberately
     * not JSON: parsing it with the default would fail, and the turn answering proves it did not.
     */
    @Test
    void turns_what_the_model_said_into_the_shape_asked_for() {
      DirectHarness<String, Lookup> harness =
          shapedHarness(
              (request, narrator) -> answering("id=42"),
              Lookup.class,
              answer -> new Lookup(answer.substring(3)));

      Outcome<Lookup> outcome = harness.ask(AgentId.random(), "which one?");

      assertThat(outcome)
          .usingRecursiveComparison()
          .ignoringFields("stats")
          .isEqualTo(new Outcome.Answered<>(new Lookup("42"), ANY_STATS));
    }

    /**
     * <b>A reader that throws fails the turn rather than the caller.</b> The door promised a value,
     * so an exception out of a reader becomes the same {@link Outcome.Failed} a model answering
     * around its schema produces -- which is the framework doing the wrapping, so a reader never
     * has to.
     */
    @Test
    void fails_the_turn_when_the_answer_is_not_that_shape_after_all() {
      DirectHarness<String, Lookup> harness =
          shapedHarness(
              (request, narrator) -> answering("nothing like an id"),
              Lookup.class,
              answer -> {
                throw new IllegalArgumentException("no id in " + answer);
              });
      AgentId agent = AgentId.random();

      Outcome<Lookup> outcome = harness.ask(agent, "which one?");

      assertThat(outcome).isInstanceOf(Outcome.Failed.class);
      assertThat(((Outcome.Failed<Lookup>) outcome).reason()).contains("no id in");
    }
  }

  @Nested
  @DisplayName("A reply the model was cut off in the middle of")
  class ATruncatedReply {

    private static InferenceResult cutOffSaying(String text) {
      return new InferenceResult.Truncated(List.of(new Block.Text(text)), Usage.unreported());
    }

    @Test
    void an_unstructured_answer_cut_off_is_delivered_as_the_answer() {
      DirectHarness<String, String> harness =
          harness((request, narrator) -> cutOffSaying("the lake is deep and"));

      Outcome<String> outcome = harness.ask(AgentId.random(), "how deep?");

      assertThat(outcome)
          .usingRecursiveComparison()
          .ignoringFields("stats")
          .isEqualTo(new Outcome.Answered<>("the lake is deep and", ANY_STATS));
    }

    @Test
    void a_structured_answer_cut_off_fails_because_it_does_not_parse() {
      DirectHarness<String, Lookup> harness =
          shapedHarness((request, narrator) -> cutOffSaying("{\"id\": \"4"), Lookup.class);

      Outcome<Lookup> outcome = harness.ask(AgentId.random(), "which one?");

      assertThat(outcome).isInstanceOf(Outcome.Failed.class);
    }
  }

  @Nested
  @DisplayName("A factory holds its providers by name, and an agent type says which")
  class AFactoryHoldsItsProvidersByName {

    private DirectHarnessFactory factoryOf(Customizer<DirectHarnessFactoryConfig> customizer) {
      return DefaultDirectHarnessFactory.of(
          f -> {
            f.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                .schemas(SCHEMAS)
                .mapper(MAPPER)
                .clock(clock);
            customizer.customize(f);
          });
    }

    @Test
    void an_agent_type_naming_a_registered_id_is_answered_by_that_provider() {
      DirectHarnessFactory factory =
          factoryOf(
              f ->
                  f.provider(ProviderId.of("first"), (request, narrator) -> answering("first"))
                      .provider(
                          ProviderId.of("second"), (request, narrator) -> answering("second")));

      DirectHarness<String, String> harness =
          factory.<String>create(
              TYPE,
              c ->
                  c.systemPrompt("You are terse.")
                      .inputRenderer(said -> List.of(new Block.Text(said)))
                      .inference(in -> in.provider("second").model("m")));

      Outcome<String> outcome = harness.ask(AgentId.random(), "hello");

      assertThat(outcome)
          .usingRecursiveComparison()
          .ignoringFields("stats")
          .isEqualTo(new Outcome.Answered<>("second", ANY_STATS));
    }

    @Test
    void an_agent_type_naming_nothing_is_answered_by_the_default() {
      DirectHarnessFactory factory =
          factoryOf(
              f ->
                  f.provider(ProviderId.of("first"), (request, narrator) -> answering("first"))
                      .provider(ProviderId.of("second"), (request, narrator) -> answering("second"))
                      .inference(ProviderId.of("first"), InferenceOptions.of("m")));

      DirectHarness<String, String> harness =
          factory.<String>create(
              TYPE,
              c ->
                  c.systemPrompt("You are terse.")
                      .inputRenderer(said -> List.of(new Block.Text(said))));

      Outcome<String> outcome = harness.ask(AgentId.random(), "hello");

      assertThat(outcome)
          .usingRecursiveComparison()
          .ignoringFields("stats")
          .isEqualTo(new Outcome.Answered<>("first", ANY_STATS));
    }

    @Test
    void an_agent_type_naming_an_unknown_provider_fails_when_the_harness_is_built() {
      DirectHarnessFactory factory =
          factoryOf(
              f -> f.provider(ProviderId.of("first"), (request, narrator) -> answering("first")));

      assertThatThrownBy(
              () ->
                  factory.<String>create(
                      TYPE,
                      c ->
                          c.systemPrompt("You are terse.")
                              .inputRenderer(said -> List.of(new Block.Text(said)))
                              .inference(in -> in.provider("claude").model("m"))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "agent type 'chat' names provider 'claude', which is not registered;"
                  + " registered: [first]");
    }

    @Test
    void no_model_and_no_default_fails_naming_the_agent_type() {
      DirectHarnessFactory factory =
          factoryOf(
              f -> f.provider(ProviderId.of("first"), (request, narrator) -> answering("first")));

      assertThatThrownBy(
              () ->
                  factory.<String>create(
                      TYPE,
                      c ->
                          c.systemPrompt("You are terse.")
                              .inputRenderer(said -> List.of(new Block.Text(said)))
                              .inference(in -> in.provider("first"))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("agent type 'chat' names no model and the factory has no default");
    }

    @Test
    void a_factory_default_with_no_max_tokens_still_builds_with_the_4096_ceiling() {
      AtomicInteger seenMaxTokens = new AtomicInteger();
      DirectHarnessFactory factory =
          factoryOf(
              f ->
                  f.provider(
                          ProviderId.of("first"),
                          (request, narrator) -> {
                            seenMaxTokens.set(request.options().maxTokens());
                            return answering("first");
                          })
                      .inference(ProviderId.of("first"), InferenceOptions.of("m")));

      DirectHarness<String, String> harness =
          factory.<String>create(
              TYPE,
              c ->
                  c.systemPrompt("You are terse.")
                      .inputRenderer(said -> List.of(new Block.Text(said))));

      harness.ask(AgentId.random(), "hello");

      assertThat(seenMaxTokens.get()).isEqualTo(4096);
    }
  }

  @Nested
  @DisplayName("vendor properties")
  class ItsVendorProperties {

    /** Answers once, remembers every set of terms it was asked to validate, refuses one name. */
    private static final class Judging implements InferenceProvider {
      final List<InferenceOptions> validated = new ArrayList<>();
      final Scripted answers = new Scripted().then(answering("ok"));

      @Override
      public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
        return answers.infer(request, narrator);
      }

      @Override
      public void validate(InferenceOptions options) {
        if (options.properties().containsKey("test.model")) {
          throw new IllegalArgumentException(
              "property 'test.model' names what InferenceConfig.model already decides;"
                  + " remove the property");
        }
        validated.add(options);
      }
    }

    private DirectHarnessFactory factory(InferenceProvider model, InferenceOptions defaults) {
      return DefaultDirectHarnessFactory.of(
          c ->
              c.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                  .provider(ProviderId.of("test"), model)
                  .inference(ProviderId.of("test"), defaults)
                  .schemas(SCHEMAS)
                  .mapper(MAPPER)
                  .clock(clock));
    }

    private static Customizer<DirectHarnessConfig<String>> agentType(
        Customizer<InferenceConfig> inference) {
      return c ->
          c.systemPrompt("You are terse.")
              .inputRenderer(said -> List.of(new Block.Text(said)))
              .inference(inference);
    }

    @Test
    void an_agent_type_s_properties_reach_the_provider_with_every_request() {
      Scripted model = new Scripted().then(answering("ok"));

      factory(model, InferenceOptions.of("a-model"))
          .<String>create(TYPE, agentType(in -> in.property("openai.reasoning.effort", "high")))
          .ask(AgentId.random(), "hi");

      assertThat(model.seen).isNotEmpty();
      assertThat(model.seen.getFirst().options().properties())
          .containsExactly(Map.entry("openai.reasoning.effort", "high"));
    }

    @Test
    void a_typed_property_reaches_the_provider_as_the_text_the_string_form_carries() {
      Scripted model = new Scripted().then(answering("ok"));
      VendorProperty<Integer> seed = VendorProperty.ofInteger("openai.seed");

      factory(model, InferenceOptions.of("a-model"))
          .<String>create(TYPE, agentType(in -> in.property(seed, 7)))
          .ask(AgentId.random(), "hi");

      assertThat(model.seen).isNotEmpty();
      assertThat(model.seen.getFirst().options().properties())
          .containsExactly(Map.entry("openai.seed", "7"));
    }

    @Test
    void factory_defaults_seed_an_agent_type_and_its_own_property_overrides_one_by_name() {
      Scripted model = new Scripted().then(answering("ok"));
      InferenceOptions defaults =
          new InferenceOptions("a-model", 0, Map.of("openai.seed", "1", "openai.store", "false"));

      factory(model, defaults)
          .<String>create(TYPE, agentType(in -> in.property("openai.seed", "2")))
          .ask(AgentId.random(), "hi");

      assertThat(model.seen.getFirst().options().properties())
          .containsOnly(Map.entry("openai.seed", "2"), Map.entry("openai.store", "false"));
    }

    @Test
    void the_provider_is_asked_to_validate_the_terms_when_the_harness_is_built() {
      Judging model = new Judging();

      factory(model, InferenceOptions.of("a-model"))
          .<String>create(
              TYPE,
              // Without chapters, whose default summariser validates the same terms again when it
              // is built; this counts the factory's own check.
              agentType(
                  in -> in.property("openai.seed", "7").context(ctx -> ctx.withoutChapters())));

      assertThat(model.validated)
          .singleElement()
          .satisfies(options -> assertThat(options.properties()).containsKey("openai.seed"));
    }

    @Test
    void a_refusal_fails_the_build_naming_the_agent_type() {
      DirectHarnessFactory factory = factory(new Judging(), InferenceOptions.of("a-model"));
      Customizer<DirectHarnessConfig<String>> clashing =
          agentType(in -> in.property("test.model", "gpt-4o"));

      assertThatThrownBy(() -> factory.<String>create(TYPE, clashing))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "agent type 'chat': property 'test.model' names what InferenceConfig.model"
                  + " already decides; remove the property");
    }

    /** §5c made checkable: properties are configuration, and nothing about them is written. */
    @Test
    void no_event_carries_a_property() {
      AgentId agent = AgentId.random();

      factory(new Scripted().then(answering("ok")), InferenceOptions.of("a-model"))
          .<String>create(TYPE, agentType(in -> in.property("openai.user", "tenant-42-secret")))
          .ask(agent, "hi");

      assertThat(events.readAll(TYPE, agent)).isNotEmpty();
      assertThat(events.readAll(TYPE, agent).toString())
          .doesNotContain("tenant-42-secret")
          .doesNotContain("openai.user");
    }

    @Test
    void a_blank_name_or_value_is_refused_at_once() {
      DefaultDirectHarnessConfig<String> config =
          new DefaultDirectHarnessConfig<>(TYPE, ObservationRegistry.NOOP);
      Customizer<InferenceConfig> blankName = in -> in.property(" ", "v");
      Customizer<InferenceConfig> blankValue = in -> in.property("openai.seed", " ");

      assertThatThrownBy(() -> config.inference(blankName))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("name must not be blank");
      assertThatThrownBy(() -> config.inference(blankValue))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("value must not be blank");
    }

    /** The harness's report line names the properties and never prints a value (§6c). */
    @Test
    void the_report_line_names_properties_and_never_their_values() {
      InferenceOptions options =
          new InferenceOptions(
              "a-model", 10, Map.of("openai.user", "tenant-42", "anthropic.top_k", "5"));

      assertThat(DefaultDirectHarnessFactory.propertyNames(options))
          .isEqualTo(", properties [anthropic.top_k, openai.user]");
      assertThat(DefaultDirectHarnessFactory.propertyNames(InferenceOptions.of("a-model")))
          .isEmpty();
    }
  }

  /**
   * One call, asked about and then run: both see the same key, the one recorded with the call, so
   * the tool can find what was decided about it and hand the key on to anything that dedupes.
   */
  @Test
  void a_calls_approval_and_its_run_carry_the_same_idempotency_key() {
    AtomicReference<IdempotencyKey> asked = new AtomicReference<>();
    AtomicReference<IdempotencyKey> ran = new AtomicReference<>();
    Approver approver =
        request -> {
          asked.set(request.idempotencyKey());
          return Awaited.ready(ApprovalResult.approved());
        };
    Tool<Lookup> tool =
        new Tool<>() {
          @Override
          public Class<Lookup> inputType() {
            return Lookup.class;
          }

          @Override
          public ToolName name() {
            return LOOKUP;
          }

          @Override
          public String description() {
            return "looks something up";
          }

          @Override
          public Awaited<ToolResult> call(ToolCallRequest<Lookup> request) {
            ran.set(request.idempotencyKey());
            return Awaited.ready(ToolResult.ok(new Block.Text("found")));
          }
        };

    harness(new Scripted().then(asking("lookup")).then(answering("done")), tool, approver)
        .ask(AgentId.random(), "look it up");

    assertThat(asked.get()).as("the approver was asked").isNotNull();
    assertThat(ran.get()).as("the run carries the approval's key").isEqualTo(asked.get());
  }

  /**
   * A turn makes a model call, and nothing should hold a transaction open across a network call.
   * The direct door refuses one before it writes anything, whatever store is behind it.
   */
  @Nested
  class InsideACallersTransaction {

    /** A real Spring transaction with no database behind it: what the door sees is the thread. */
    private static final class Bare extends AbstractPlatformTransactionManager {
      @Override
      protected Object doGetTransaction() {
        return new Object();
      }

      @Override
      protected void doBegin(Object transaction, TransactionDefinition definition) {
        // Nothing to open.
      }

      @Override
      protected void doCommit(DefaultTransactionStatus status) {
        // Nothing to commit.
      }

      @Override
      protected void doRollback(DefaultTransactionStatus status) {
        // Nothing to roll back.
      }
    }

    private final TransactionTemplate transaction = new TransactionTemplate(new Bare());

    @Test
    void ask_is_refused_before_anything_is_asked_or_written() {
      Scripted model = new Scripted().then(answering("forty two"));
      DirectHarness<String, String> harness = harness(model);
      AgentId agent = AgentId.random();

      Throwable refused =
          transaction.execute(
              status -> catchThrowable(() -> harness.ask(agent, "what is the answer?")));

      assertThat(refused)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cannot run inside a caller's transaction")
          .hasMessageContaining("PROPAGATION_NOT_SUPPORTED");
      assertThat(model.seen).as("no model call was made").isEmpty();
    }

    @Test
    void the_same_agent_answers_once_the_transaction_is_over() {
      Scripted model = new Scripted().then(answering("forty two"));
      DirectHarness<String, String> harness = harness(model);
      AgentId agent = AgentId.random();
      transaction.executeWithoutResult(
          status -> catchThrowable(() -> harness.ask(agent, "what is the answer?")));

      Outcome<String> outcome = harness.ask(agent, "what is the answer?");

      assertThat(outcome)
          .usingRecursiveComparison()
          .ignoringFields("stats")
          .isEqualTo(new Outcome.Answered<>("forty two", ANY_STATS));
    }
  }
}
