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
package org.jwcarman.nessy.engine.story;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/** What a listener hears on the queued door is what the story replays, retries included. */
@Tag("container")
class EventAgentStoriesQueuedTest {

  private final List<Narrated> heard = new CopyOnWriteArrayList<>();

  private static InferenceProvider callsThenAnswers() {
    return (request, narrator) ->
        request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
            ? new InferenceResult.Answer(
                List.of(new Block.Text("It is 1412 metres deep.")), Usage.of("a-model", 40, 9))
            : new InferenceResult.Actions(
                List.of(
                    new Block.Commentary("Let me look that up."),
                    new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")),
                Usage.of("a-model", 25, 6));
  }

  /** Fails once, as a provider does when it is busy, and then behaves as callsThenAnswers. */
  private static InferenceProvider busyOnceThenCallsAndAnswers() {
    AtomicBoolean failed = new AtomicBoolean();
    InferenceProvider behaving = callsThenAnswers();
    return (request, narrator) ->
        failed.compareAndSet(false, true)
            ? new InferenceResult.Fault(new Failure.Transient("busy"), Usage.of("a-model", 5, 0))
            : behaving.infer(request, narrator);
  }

  private List<Narrated> replayed(EngineFixture engine, AgentType type, AgentId agent) {
    return new EventAgentStories(engine.events(), engine.payloads())
        .of(type, agent)
        .replay(Seq.NONE, 100);
  }

  @Test
  void a_turn_with_a_tool_call_heard_live_is_what_the_replay_returns() {
    AgentType type = new AgentType("queued-story");
    AgentId agent = AgentId.random();
    NarrationListener recording = heard::add;

    try (EngineFixture engine = new EngineFixture(callsThenAnswers(), recording)) {
      engine
          .harnesses()
          .<String>create(
              type,
              String.class,
              config ->
                  config
                      .systemPrompt("You are a test assistant.")
                      .tool(
                          StoryTurn.lookup(),
                          t ->
                              t.action(query -> "looked up " + query.q())
                                  .approver(StoryTurn.decidesAsCarol()))
                      .inference(in -> in.model("a-model"))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .tell(agent, "how deep is Loch Ness?");
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () ->
                  heard.stream()
                      .anyMatch(narrated -> narrated.event() instanceof Narration.Answered));

      List<Narrated> story =
          heard.stream().filter(narrated -> narrated.event() instanceof Narration.Story).toList();
      assertThat(story)
          .extracting(narrated -> narrated.event().getClass().getSimpleName())
          .containsSubsequence(
              "TurnStarted", "ActionsRequested", "CallApproved", "CallFinished", "Answered");
      assertThat(replayed(engine, type, agent)).isEqualTo(story);
      assertCallEventsCarryTheRequestedKey(story);
      assertCallEventsCarryTheRequestedKey(replayed(engine, type, agent));
    }
  }

  @Test
  void a_reply_cut_off_at_the_output_limit_reads_truncated_live_and_replayed() {
    AgentType type = new AgentType("queued-truncated");
    AgentId agent = AgentId.random();
    NarrationListener recording = heard::add;

    try (EngineFixture engine = new EngineFixture(StoryTurn.cutOffAtTheOutputLimit(), recording)) {
      engine
          .harnesses()
          .<String>create(
              type,
              String.class,
              config ->
                  config
                      .systemPrompt("You are a test assistant.")
                      .inference(in -> in.model("a-model"))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .tell(agent, "how deep is Loch Ness?");
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () ->
                  heard.stream()
                      .anyMatch(narrated -> narrated.event() instanceof Narration.Answered));

      List<Narrated> story =
          heard.stream().filter(narrated -> narrated.event() instanceof Narration.Story).toList();
      assertThat(story)
          .map(Narrated::event)
          .filteredOn(Narration.Answered.class::isInstance)
          .singleElement()
          .extracting(event -> ((Narration.Answered) event).truncated())
          .isEqualTo(true);
      assertThat(replayed(engine, type, agent)).isEqualTo(story);
    }
  }

  @Test
  void a_turn_start_reads_its_label_and_arrival_live_and_replayed() {
    AgentType type = new AgentType("queued-label");
    AgentId agent = AgentId.random();
    NarrationListener recording = heard::add;

    try (EngineFixture engine = new EngineFixture(StoryTurn.cutOffAtTheOutputLimit(), recording)) {
      engine
          .harnesses()
          .<String>create(
              type,
              String.class,
              config ->
                  config
                      .systemPrompt("You are a test assistant.")
                      .inputLabel(said -> "Question")
                      .inference(in -> in.model("a-model"))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .tell(agent, "how deep is Loch Ness?");
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () ->
                  heard.stream()
                      .anyMatch(narrated -> narrated.event() instanceof Narration.Answered));

      List<Narrated> story =
          heard.stream().filter(narrated -> narrated.event() instanceof Narration.Story).toList();
      assertThat(story)
          .map(Narrated::event)
          .filteredOn(Narration.TurnStarted.class::isInstance)
          .singleElement()
          .extracting(event -> ((Narration.TurnStarted) event).label())
          .isEqualTo("Question");
      assertThat(story)
          .map(Narrated::event)
          .filteredOn(Narration.TurnStarted.class::isInstance)
          .singleElement()
          .extracting(event -> ((Narration.TurnStarted) event).arrivedAt())
          .isNotNull();
      assertThat(replayed(engine, type, agent)).isEqualTo(story);
    }
  }

  @Test
  void a_retried_model_call_heard_live_is_what_the_replay_returns() {
    AgentType type = new AgentType("queued-retry");
    AgentId agent = AgentId.random();
    NarrationListener recording = heard::add;

    try (EngineFixture engine = new EngineFixture(busyOnceThenCallsAndAnswers(), recording)) {
      engine
          .harnesses()
          .<String>create(
              type,
              String.class,
              config ->
                  config
                      .systemPrompt("You are a test assistant.")
                      .tool(
                          StoryTurn.lookup(),
                          t ->
                              t.action(query -> "looked up " + query.q())
                                  .approver(StoryTurn.decidesAsCarol()))
                      .inference(
                          in ->
                              in.model("a-model")
                                  .retryPolicy(
                                      new RetryPolicy.FixedDelay(
                                          2, Duration.ofMillis(100), Duration.ZERO)))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .tell(agent, "how deep is Loch Ness?");
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () ->
                  heard.stream()
                      .anyMatch(narrated -> narrated.event() instanceof Narration.Answered));

      List<Narrated> story =
          heard.stream().filter(narrated -> narrated.event() instanceof Narration.Story).toList();
      assertThat(story)
          .extracting(narrated -> narrated.event().getClass().getSimpleName())
          .containsSubsequence("TurnStarted", "InferenceRetried", "ActionsRequested", "Answered");
      assertThat(replayed(engine, type, agent)).isEqualTo(story);
    }
  }

  /** A lookup that hands the work off and never reports back. */
  private static Tool<StoryTurn.Query> lookupThatNeverReportsBack() {
    return new Tool<>() {
      @Override
      public Class<StoryTurn.Query> inputType() {
        return StoryTurn.Query.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("lookup");
      }

      @Override
      public String description() {
        return "looks a thing up, eventually";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<StoryTurn.Query> request) {
        return Awaited.deferred();
      }
    };
  }

  @Test
  void a_call_that_fails_at_its_deadline_says_so_heard_live_and_replayed() {
    AgentType type = new AgentType("queued-deadline");
    AgentId agent = AgentId.random();
    NarrationListener recording = heard::add;

    try (EngineFixture engine = new EngineFixture(callsThenAnswers(), recording)) {
      engine
          .harnesses()
          .<String>create(
              type,
              String.class,
              config ->
                  config
                      .systemPrompt("You are a test assistant.")
                      .tool(
                          lookupThatNeverReportsBack(),
                          t ->
                              t.timeout(Duration.ofSeconds(2))
                                  .action(query -> "looked up " + query.q())
                                  .approver(StoryTurn.decidesAsCarol()))
                      .inference(in -> in.model("a-model"))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .tell(agent, "how deep is Loch Ness?");
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () ->
                  heard.stream()
                      .anyMatch(narrated -> narrated.event() instanceof Narration.Answered));

      List<Narrated> story =
          heard.stream().filter(narrated -> narrated.event() instanceof Narration.Story).toList();
      assertThat(story)
          .map(Narrated::event)
          .filteredOn(Narration.CallFailed.class::isInstance)
          .singleElement()
          .extracting(event -> ((Narration.CallFailed) event).kind())
          .isEqualTo(CallFailure.PAST_DEADLINE);
      assertThat(replayed(engine, type, agent)).isEqualTo(story);
      assertTheCallDeferralCarriesTheRequestedKeyAndTheStoredDeadline(engine, type, agent, story);
      assertThat(story)
          .extracting(narrated -> narrated.event().getClass().getSimpleName())
          .containsSubsequence("ActionsRequested", "CallApproved", "CallDeferred", "CallFailed");
      assertTheFailedCallCarriesTheRequestedKey(story);
      assertTheFailedCallCarriesTheRequestedKey(replayed(engine, type, agent));
    }
  }

  @Test
  void a_denied_call_reads_the_same_live_and_replayed() {
    AgentType type = new AgentType("queued-denied");
    AgentId agent = AgentId.random();
    NarrationListener recording = heard::add;

    try (EngineFixture engine = new EngineFixture(callsThenAnswers(), recording)) {
      engine
          .harnesses()
          .<String>create(
              type,
              String.class,
              config ->
                  config
                      .systemPrompt("You are a test assistant.")
                      .tool(
                          StoryTurn.lookup(),
                          t ->
                              t.action(query -> "looked up " + query.q())
                                  .approver(StoryTurn.deniesAsDave()))
                      .inference(in -> in.model("a-model"))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .tell(agent, "how deep is Loch Ness?");
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () ->
                  heard.stream()
                      .anyMatch(narrated -> narrated.event() instanceof Narration.Answered));

      List<Narrated> story =
          heard.stream().filter(narrated -> narrated.event() instanceof Narration.Story).toList();
      assertThat(replayed(engine, type, agent)).isEqualTo(story);
      assertTheDenialCarriesTheRequestedKey(story);
      assertTheDenialCarriesTheRequestedKey(replayed(engine, type, agent));
    }
  }

  /** A lookup whose call hands the work off, keeps the address, and reports back later. */
  private static Tool<StoryTurn.Query> lookupThatReportsLater(
      ConcurrentLinkedQueue<ReplyToken> handed) {
    return new Tool<>() {
      @Override
      public Class<StoryTurn.Query> inputType() {
        return StoryTurn.Query.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("lookup");
      }

      @Override
      public String description() {
        return "looks a thing up, eventually";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<StoryTurn.Query> request) {
        handed.add(request.replyToken());
        return Awaited.deferred();
      }
    };
  }

  /** An approver that keeps the address and says nothing: the whole of a deferring approver. */
  private static Approver defersAndKeepsTheAddress(ConcurrentLinkedQueue<ReplyToken> handed) {
    return request -> {
      handed.add(request.replyToken());
      return Awaited.deferred();
    };
  }

  private static Instant deadlineOfTheOneRow(EngineFixture engine, AgentType type) {
    return engine
        .jdbc()
        .sql("SELECT deadline FROM nessy_agent_effect WHERE agent_type = ?")
        .params(type.value())
        .query(OffsetDateTime.class)
        .single()
        .toInstant()
        .truncatedTo(ChronoUnit.MICROS);
  }

  private List<Narrated> heardStory() {
    return heard.stream().filter(narrated -> narrated.event() instanceof Narration.Story).toList();
  }

  private void awaitHeard(Class<? extends Narration> kind) {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> heard.stream().anyMatch(narrated -> kind.isInstance(narrated.event())));
  }

  @Test
  void a_deferred_approval_reads_the_same_heard_live_and_replayed() {
    AgentType type = new AgentType("queued-approval-deferred");
    AgentId agent = AgentId.random();
    NarrationListener recording = heard::add;
    ConcurrentLinkedQueue<ReplyToken> handed = new ConcurrentLinkedQueue<>();

    try (EngineFixture engine = new EngineFixture(callsThenAnswers(), recording)) {
      engine
          .harnesses()
          .<String>create(
              type,
              String.class,
              config ->
                  config
                      .systemPrompt("You are a test assistant.")
                      .tool(
                          StoryTurn.lookup(),
                          t ->
                              t.action(query -> "looked up " + query.q())
                                  .approver(
                                      defersAndKeepsTheAddress(handed),
                                      a -> a.timeout(Duration.ofMinutes(30))))
                      .inference(in -> in.model("a-model"))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .tell(agent, "how deep is Loch Ness?");
      awaitHeard(Narration.ApprovalDeferred.class);
      Instant rowDeadline = deadlineOfTheOneRow(engine, type);
      assertThat(handed).hasSize(1);
      engine.replies().approve(handed.peek(), ApprovalResult.approvedBy(StoryTurn.DECIDER));
      awaitHeard(Narration.Answered.class);

      List<Narrated> story = heardStory();
      assertThat(replayed(engine, type, agent)).isEqualTo(story);
      assertThat(story)
          .extracting(narrated -> narrated.event().getClass().getSimpleName())
          .containsSubsequence(
              "ActionsRequested", "ApprovalDeferred", "CallApproved", "CallFinished", "Answered");
      assertThat(story)
          .map(Narrated::event)
          .filteredOn(Narration.ApprovalDeferred.class::isInstance)
          .singleElement()
          .satisfies(
              event -> {
                Narration.ApprovalDeferred deferred = (Narration.ApprovalDeferred) event;
                assertThat(deferred.idempotencyKey()).isEqualTo(StoryTurn.requestedKey(story));
                assertThat(deferred.until()).isEqualTo(rowDeadline);
              });
    }
  }

  @Test
  void a_deferred_approval_that_expires_reads_the_same_heard_live_and_replayed() {
    AgentType type = new AgentType("queued-approval-expires");
    AgentId agent = AgentId.random();
    NarrationListener recording = heard::add;
    ConcurrentLinkedQueue<ReplyToken> handed = new ConcurrentLinkedQueue<>();

    try (EngineFixture engine = new EngineFixture(callsThenAnswers(), recording)) {
      engine
          .harnesses()
          .<String>create(
              type,
              String.class,
              config ->
                  config
                      .systemPrompt("You are a test assistant.")
                      .tool(
                          StoryTurn.lookup(),
                          t ->
                              t.action(query -> "looked up " + query.q())
                                  .approver(
                                      defersAndKeepsTheAddress(handed),
                                      a -> a.timeout(Duration.ofSeconds(2))))
                      .inference(in -> in.model("a-model"))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .tell(agent, "how deep is Loch Ness?");
      awaitHeard(Narration.Answered.class);

      List<Narrated> story = heardStory();
      assertThat(replayed(engine, type, agent)).isEqualTo(story);
      assertThat(story)
          .extracting(narrated -> narrated.event().getClass().getSimpleName())
          .containsSubsequence("ActionsRequested", "ApprovalDeferred", "CallFailed");
      assertThat(story)
          .map(Narrated::event)
          .filteredOn(Narration.CallFailed.class::isInstance)
          .singleElement()
          .satisfies(
              event -> {
                Narration.CallFailed failed = (Narration.CallFailed) event;
                assertThat(failed.kind()).isEqualTo(CallFailure.NOT_AUTHORISED);
                assertThat(failed.idempotencyKey()).isEqualTo(StoryTurn.requestedKey(story));
              });
    }
  }

  @Test
  void a_deferred_tool_call_reads_the_same_heard_live_and_replayed() {
    AgentType type = new AgentType("queued-call-deferred");
    AgentId agent = AgentId.random();
    NarrationListener recording = heard::add;
    ConcurrentLinkedQueue<ReplyToken> handed = new ConcurrentLinkedQueue<>();

    try (EngineFixture engine = new EngineFixture(callsThenAnswers(), recording)) {
      engine
          .harnesses()
          .<String>create(
              type,
              String.class,
              config ->
                  config
                      .systemPrompt("You are a test assistant.")
                      .tool(
                          lookupThatReportsLater(handed),
                          t ->
                              t.timeout(Duration.ofMinutes(30))
                                  .action(query -> "looked up " + query.q())
                                  .approver(StoryTurn.decidesAsCarol()))
                      .inference(in -> in.model("a-model"))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .tell(agent, "how deep is Loch Ness?");
      awaitHeard(Narration.CallDeferred.class);
      Instant rowDeadline = deadlineOfTheOneRow(engine, type);
      assertThat(handed).hasSize(1);
      engine.replies().complete(handed.peek(), ToolResult.ok(new Block.Text("1412 metres")));
      awaitHeard(Narration.Answered.class);

      List<Narrated> story = heardStory();
      assertThat(replayed(engine, type, agent)).isEqualTo(story);
      assertThat(story)
          .extracting(narrated -> narrated.event().getClass().getSimpleName())
          .containsSubsequence(
              "ActionsRequested", "CallApproved", "CallDeferred", "CallFinished", "Answered");
      assertThat(story)
          .map(Narrated::event)
          .filteredOn(Narration.CallDeferred.class::isInstance)
          .singleElement()
          .satisfies(
              event -> {
                Narration.CallDeferred deferred = (Narration.CallDeferred) event;
                assertThat(deferred.idempotencyKey()).isEqualTo(StoryTurn.requestedKey(story));
                assertThat(deferred.until()).isEqualTo(rowDeadline);
              });
    }
  }

  @Test
  void
      a_turn_with_a_retry_a_gated_call_a_deferral_and_an_answer_reads_the_same_heard_live_and_replayed() {
    AgentType type = new AgentType("queued-scripted-turn");
    AgentId agent = AgentId.random();
    NarrationListener recording = heard::add;
    ConcurrentLinkedQueue<ReplyToken> handed = new ConcurrentLinkedQueue<>();

    try (EngineFixture engine = new EngineFixture(busyOnceThenCallsAndAnswers(), recording)) {
      engine
          .harnesses()
          .<String>create(
              type,
              String.class,
              config ->
                  config
                      .systemPrompt("You are a test assistant.")
                      .tool(
                          StoryTurn.lookup(),
                          t ->
                              t.action(query -> "looked up " + query.q())
                                  .approver(
                                      defersAndKeepsTheAddress(handed),
                                      a -> a.timeout(Duration.ofMinutes(30))))
                      .inference(
                          in ->
                              in.model("a-model")
                                  .retryPolicy(
                                      new RetryPolicy.FixedDelay(
                                          2, Duration.ofMillis(100), Duration.ZERO)))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .tell(agent, "how deep is Loch Ness?");
      awaitHeard(Narration.ApprovalDeferred.class);
      assertThat(handed).hasSize(1);
      engine.replies().approve(handed.peek(), ApprovalResult.approvedBy(StoryTurn.DECIDER));
      awaitHeard(Narration.Answered.class);

      List<Narrated> story = heardStory();
      assertThat(story)
          .extracting(narrated -> narrated.event().getClass().getSimpleName())
          .containsExactly(
              "TurnStarted",
              "InferenceRetried",
              "ActionsRequested",
              "ApprovalDeferred",
              "CallApproved",
              "CallFinished",
              "Answered");
      assertThat(replayed(engine, type, agent)).isEqualTo(story);
    }
  }

  /** The one call deferral told carries the request's key and the deadline the story stored. */
  private static void assertTheCallDeferralCarriesTheRequestedKeyAndTheStoredDeadline(
      EngineFixture engine, AgentType type, AgentId agent, List<Narrated> story) {
    List<AgentEvent.ToolDeferred> stored =
        engine.story(type, agent).stream()
            .filter(AgentEvent.ToolDeferred.class::isInstance)
            .map(AgentEvent.ToolDeferred.class::cast)
            .toList();
    assertThat(stored).hasSize(1);
    assertThat(story)
        .map(Narrated::event)
        .filteredOn(Narration.CallDeferred.class::isInstance)
        .singleElement()
        .satisfies(
            event -> {
              Narration.CallDeferred deferred = (Narration.CallDeferred) event;
              assertThat(deferred.idempotencyKey()).isEqualTo(StoryTurn.requestedKey(story));
              assertThat(deferred.until()).isEqualTo(stored.getFirst().until());
            });
  }

  /** Every call event told, live or replayed, carries the key its request gave the call. */
  private static void assertCallEventsCarryTheRequestedKey(List<Narrated> story) {
    IdempotencyKey requested = StoryTurn.requestedKey(story);
    assertThat(story)
        .map(Narrated::event)
        .filteredOn(Narration.CallApproved.class::isInstance)
        .singleElement()
        .extracting(event -> ((Narration.CallApproved) event).idempotencyKey())
        .isEqualTo(requested);
    assertThat(story)
        .map(Narrated::event)
        .filteredOn(Narration.CallApproved.class::isInstance)
        .singleElement()
        .extracting(event -> ((Narration.CallApproved) event).decidedBy())
        .isEqualTo(Optional.of(StoryTurn.DECIDER));
    assertThat(story)
        .map(Narrated::event)
        .filteredOn(Narration.CallFinished.class::isInstance)
        .singleElement()
        .extracting(event -> ((Narration.CallFinished) event).idempotencyKey())
        .isEqualTo(requested);
  }

  /** The one denial told, live or replayed, carries the request's key and the one who refused. */
  private static void assertTheDenialCarriesTheRequestedKey(List<Narrated> story) {
    IdempotencyKey requested = StoryTurn.requestedKey(story);
    assertThat(story)
        .map(Narrated::event)
        .filteredOn(Narration.CallDenied.class::isInstance)
        .singleElement()
        .satisfies(
            event -> {
              Narration.CallDenied denied = (Narration.CallDenied) event;
              assertThat(denied.idempotencyKey()).isEqualTo(requested);
              assertThat(denied.decidedBy()).isEqualTo(Optional.of(StoryTurn.DENIER));
            });
  }

  /** The one failed call told, live or replayed, carries the key its request gave it. */
  private static void assertTheFailedCallCarriesTheRequestedKey(List<Narrated> story) {
    assertThat(story)
        .map(Narrated::event)
        .filteredOn(Narration.CallFailed.class::isInstance)
        .singleElement()
        .extracting(event -> ((Narration.CallFailed) event).idempotencyKey())
        .isEqualTo(StoryTurn.requestedKey(story));
  }
}
