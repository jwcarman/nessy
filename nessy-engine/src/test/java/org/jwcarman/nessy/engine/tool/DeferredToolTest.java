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
package org.jwcarman.nessy.engine.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Stringifier;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolConfig;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.ParkRefusingBackend;
import org.jwcarman.nessy.engine.agent.OutstandingAction;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * A tool that starts work and answers later.
 *
 * <p>The same machinery as a deferred approval and a different shape of thing to wait for: an
 * approval waits on a person deciding, a tool waits on work being done. The engine cannot tell them
 * apart and must not -- which is exactly what these pin, because the one place they are told apart
 * is which parked row an answer may settle.
 *
 * <p>Permission is granted here on the spot, so what parks is the call itself rather than the
 * question about it. That order matters: a tool cannot defer until it has been allowed to run.
 */
class DeferredToolTest {

  private static EngineFixture engine;

  @BeforeAll
  static void startEngine() {
    engine = new EngineFixture(MODEL);
  }

  @AfterAll
  static void stopEngine() {
    engine.close();
  }

  /**
   * The provider belongs to the factory, not to a harness -- one model serves every agent type an
   * engine runs. This one asks for the job on the first pass and answers once the job has reported,
   * which is the whole shape a deferred call has to survive.
   */
  private static final InferenceProvider MODEL =
      (request, _) ->
          request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
              ? new InferenceResult.Answer(List.of(new Block.Text("all done")))
              : new InferenceResult.Actions(
                  List.of(new Block.ToolCall("call_1", "start_job", "{\"what\":\"reindex\"}")));

  record Job(String what) {}

  private final ConcurrentLinkedQueue<ReplyToken> handed = new ConcurrentLinkedQueue<>();

  /** Starts something and says it will report back -- a queue, a build, a long HTTP call. */
  private Tool<Job> slowJob() {
    return slowJob(
        request -> {
          // Whatever actually does the work is handed the address. Keeping it is the tool's
          // obligation exactly as it is an approver's: nothing else can settle this call.
          handed.add(request.replyToken());
          return Awaited.deferred();
        });
  }

  private Tool<Job> slowJob(Function<ToolCallRequest<Job>, Awaited<ToolResult>> behaviour) {
    return new Tool<>() {
      @Override
      public Class<Job> inputType() {
        return Job.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("start_job");
      }

      @Override
      public String description() {
        return "starts a long job and reports back when it finishes";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Job> request) {
        return behaviour.apply(request);
      }
    };
  }

  private QueuedHarness<String> harness(
      AgentType type, Duration toolBudget, Customizer<ToolConfig<Job>> more) {
    return harness(engine, type, toolBudget, more, slowJob());
  }

  private QueuedHarness<String> harness(
      EngineFixture fixture,
      AgentType type,
      Duration toolBudget,
      Customizer<ToolConfig<Job>> more,
      Tool<Job> job) {
    return fixture
        .harnesses()
        .create(
            type,
            String.class,
            config ->
                config
                    .systemPrompt("You are a test assistant.")
                    // Nothing gates it: permission is granted at once, so the call itself is what
                    // parks. A tool cannot defer before it has been allowed to run.
                    .tool(
                        job,
                        t -> {
                          t.timeout(toolBudget);
                          more.customize(t);
                        })
                    .inference(in -> in.model("a-model"))
                    .effects(e -> e.pollInterval(Duration.ofMillis(50))));
  }

  /** The state replay produces, named -- there is no state column to read. */
  private String agentStateOf(AgentType agentType, AgentId agentId) {
    return engine.stateOf(agentType, agentId).getClass().getSimpleName();
  }

  private int outstandingEffects(AgentId agentId) {
    return engine
        .jdbc()
        .sql("SELECT count(*) FROM nessy_agent_effect WHERE agent_id = ?")
        .params(agentId.value())
        .query(Integer.class)
        .single();
  }

  /** What the effect table holds for the one effect of a test's agent type. */
  private record Row(
      OffsetDateTime parkedAt,
      OffsetDateTime actionableAt,
      OffsetDateTime deadline,
      String status) {}

  private static Row rowOf(EngineFixture fixture, AgentType type) {
    return fixture
        .jdbc()
        .sql(
            "SELECT parked_at, actionable_at, deadline, status FROM nessy_agent_effect"
                + " WHERE agent_type = ?")
        .params(type.value())
        .query(
            (rs, n) ->
                new Row(
                    rs.getObject("parked_at", OffsetDateTime.class),
                    rs.getObject("actionable_at", OffsetDateTime.class),
                    rs.getObject("deadline", OffsetDateTime.class),
                    rs.getString("status")))
        .single();
  }

  private static List<AgentEvent.ToolDeferred> deferralsIn(List<AgentEvent> story) {
    return story.stream()
        .filter(AgentEvent.ToolDeferred.class::isInstance)
        .map(AgentEvent.ToolDeferred.class::cast)
        .toList();
  }

  private AgentId park(AgentType type, Duration toolBudget) {
    return park(type, toolBudget, Customizer.withDefaults());
  }

  private AgentId park(AgentType type, Duration toolBudget, Customizer<ToolConfig<Job>> more) {
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, toolBudget, more).tell(agentId, "kick off the reindex");
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handed).hasSize(1));
    return agentId;
  }

  @Test
  void aToolThatReportsBackLaterFinishesTheTurnWhenItDoes() {
    AgentType type = new AgentType("deferred-tool");
    AgentId agentId = park(type, Duration.ofMinutes(30));

    // Approved and dispatched, and then waiting on work rather than on a decision.
    assertThat(agentStateOf(type, agentId)).isEqualTo("AwaitingActions");
    assertThat(outstandingEffects(agentId)).isEqualTo(1);
    assertThat(engine.story(type, agentId))
        .as("permission was granted before the tool ever ran")
        .anyMatch(AgentEvent.ToolApproved.class::isInstance);

    assertThat(
            engine.replies().complete(handed.peek(), ToolResult.ok(new Block.Text("reindexed 91"))))
        .isInstanceOf(ReplyOutcome.Settled.class);

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(type, agentId)).isEqualTo("Idle");
              assertThat(outstandingEffects(agentId)).isZero();
            });

    List<AgentEvent> story = engine.story(type, agentId);
    assertThat(story.get(3))
        .as("the deferral is on the record, after the grant and before the result")
        .isInstanceOf(AgentEvent.ToolDeferred.class);
    assertThat(story.get(4))
        .isEqualTo(
            new AgentEvent.ToolSucceeded(
                new Seq(5),
                new TurnId(1),
                new CallId("call_1"),
                engine.ref(agentId, List.of(new Block.Text("reindexed 91"))),
                "reindexed 91",
                requestedKey(story)));
    assertThat(story.get(5)).isInstanceOf(AgentEvent.InferenceAnswered.class);
  }

  /**
   * A result that arrives later is recorded by a different class from one that returns on the spot,
   * so the line is made in two places. The tool's own binding must speak for both.
   */
  @Test
  void a_result_that_arrives_later_carries_its_rendered_line_too() {
    AgentType type = new AgentType("deferred-tool-rendered");
    Stringifier<ToolResult.Success> line =
        success -> "job finished: " + ToolConfig.resultText().stringify(success);
    AgentId agentId = park(type, Duration.ofMinutes(30), t -> t.result(line));

    ToolResult.Success reported = new ToolResult.Success(List.of(new Block.Text("reindexed 91")));
    assertThat(engine.replies().complete(handed.peek(), reported))
        .isInstanceOf(ReplyOutcome.Settled.class);

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(agentStateOf(type, agentId)).isEqualTo("Idle"));

    assertThat(engine.story(type, agentId).get(3)).isInstanceOf(AgentEvent.ToolDeferred.class);
    assertThat(engine.story(type, agentId).get(4))
        .asInstanceOf(type(AgentEvent.ToolSucceeded.class))
        .extracting(AgentEvent.ToolSucceeded::rendered)
        .isEqualTo(line.stringify(reported))
        .isEqualTo("job finished: reindexed 91");
  }

  /** Work that finished badly is still an answer, and discharges the call the same way. */
  @Test
  void aJobThatFailsLaterIsReportedToTheModel() {
    AgentType type = new AgentType("deferred-tool-failed");
    AgentId agentId = park(type, Duration.ofMinutes(30));

    assertThat(
            engine
                .replies()
                .complete(
                    handed.peek(), new ToolResult.Failure("the index is locked by another job")))
        .isInstanceOf(ReplyOutcome.Settled.class);

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(agentStateOf(type, agentId)).isEqualTo("Idle"));

    assertThat(engine.story(type, agentId).get(3)).isInstanceOf(AgentEvent.ToolDeferred.class);
    assertThat(engine.story(type, agentId).get(4))
        .asInstanceOf(type(AgentEvent.ToolFailed.class))
        .satisfies(
            failed -> {
              assertThat(failed.callId()).isEqualTo(new CallId("call_1"));
              assertThat(failed.message()).isEqualTo("the index is locked by another job");
            });
  }

  /** Twice is refused the second time, the same as for an approval. */
  @Test
  void reportingTheSameJobTwiceIsRefused() {
    AgentType type = new AgentType("deferred-tool-twice");
    park(type, Duration.ofMinutes(30));

    assertThat(engine.replies().complete(handed.peek(), ToolResult.ok(new Block.Text("done"))))
        .isInstanceOf(ReplyOutcome.Settled.class);
    assertThat(
            engine.replies().complete(handed.peek(), ToolResult.ok(new Block.Text("done again"))))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
  }

  /**
   * The one place the two kinds of deferral are told apart.
   *
   * <p>Both handlers mint their address from the same coordinates -- agent, request, call -- so a
   * token for a parked tool decodes to exactly what a token for its approval would have. What
   * separates them is which kind of effect the answer is allowed to settle. Without that, a verdict
   * could settle a running tool.
   */
  @Test
  void aVerdictCannotAnswerAJobThatIsAlreadyRunning() {
    AgentType type = new AgentType("deferred-tool-wrong-kind");
    park(type, Duration.ofMinutes(30));

    assertThat(engine.replies().approve(handed.peek(), ApprovalResult.approved()))
        .as("permission was settled before this call ever started")
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
    assertThat(engine.replies().complete(handed.peek(), ToolResult.ok(new Block.Text("done"))))
        .as("and the right kind of answer still works")
        .isInstanceOf(ReplyOutcome.Settled.class);
  }

  /**
   * Nobody reported back, so the agent stops waiting and is told something.
   *
   * <p>What it is told matters: the tool <em>was</em> dispatched and may well have done work.
   * Saying otherwise would tell the model nothing happened when something might have.
   */
  @Test
  void aJobThatNeverReportsBackExpiresIntoAFailure() {
    AgentType type = new AgentType("deferred-tool-expired");
    AgentId agentId = park(type, Duration.ofSeconds(2));

    await()
        .atMost(Duration.ofSeconds(25))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(type, agentId)).isEqualTo("Idle");
              assertThat(outstandingEffects(agentId)).isZero();
            });

    assertThat(engine.story(type, agentId).get(3)).isInstanceOf(AgentEvent.ToolDeferred.class);
    assertThat(engine.story(type, agentId).get(4))
        .asInstanceOf(type(AgentEvent.ToolFailed.class))
        .satisfies(
            failed -> {
              assertThat(failed.callId()).isEqualTo(new CallId("call_1"));
              assertThat(failed.message())
                  .as(
                      "a deferred call may well have run, so nothing may claim it did"
                          + " not -- that is the direction that invites a repeat")
                  .doesNotContain("was not run")
                  .contains("whether it ran is not known");
            });
  }

  /** And an answer arriving after the agent gave up is refused rather than lost. */
  @Test
  void anAnswerAfterTheJobGaveUpIsRefused() {
    AgentType type = new AgentType("deferred-tool-late");
    AgentId agentId = park(type, Duration.ofSeconds(2));

    await()
        .atMost(Duration.ofSeconds(25))
        .untilAsserted(() -> assertThat(agentStateOf(type, agentId)).isEqualTo("Idle"));

    assertThat(engine.replies().complete(handed.peek(), ToolResult.ok(new Block.Text("too late"))))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
  }

  // ---- the deferral is on the record -------------------------------------------------------

  /** Recorded when it happens: the story holds the deferral before anyone has reported back. */
  @Test
  void a_deferred_tool_is_on_the_record_when_it_happens() {
    AgentType type = new AgentType("deferred-tool-on-record");
    AgentId agentId = park(type, Duration.ofMinutes(30));

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(deferralsIn(engine.story(type, agentId))).hasSize(1));

    List<AgentEvent> story = engine.story(type, agentId);
    assertThat(story.get(3)).isInstanceOf(AgentEvent.ToolDeferred.class);
    AgentEvent.ToolDeferred deferred = (AgentEvent.ToolDeferred) story.get(3);
    assertThat(deferred.seq()).isEqualTo(new Seq(4));
    assertThat(deferred.turn()).isEqualTo(new TurnId(1));
    assertThat(deferred.callId()).isEqualTo(new CallId("call_1"));
    assertThat(deferred.idempotencyKey()).as("the call's own key").isEqualTo(requestedKey(story));
    assertThat(deferred.until())
        .as("until is the instant the row holds the call to")
        .isEqualTo(rowOf(engine, type).deadline().toInstant().truncatedTo(ChronoUnit.MICROS));
    assertThat(story)
        .as("nobody has reported back yet")
        .noneMatch(AgentEvent.ToolSucceeded.class::isInstance);
  }

  /** The row is marked, and it comes due once more at the deadline and not before. */
  @Test
  void a_parked_row_is_marked_and_due_at_its_deadline() {
    AgentType type = new AgentType("deferred-tool-parked-row");
    AgentId agentId = park(type, Duration.ofMinutes(30));

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(deferralsIn(engine.story(type, agentId))).hasSize(1));

    Row row = rowOf(engine, type);
    assertThat(row.parkedAt()).as("marked as parked").isNotNull();
    assertThat(row.actionableAt().toInstant())
        .as("due at its deadline")
        .isEqualTo(row.deadline().toInstant());
    assertThat(row.status()).as("still the attempt that is waiting").isEqualTo("RUNNING");
  }

  /**
   * The event and the mark stand or fall together. With the effect table refusing the mark, the
   * event appended a moment earlier in the same step is rolled back with it, nothing is narrated,
   * and the call still waits for its report.
   */
  @Test
  void the_event_and_the_mark_are_one_transaction() {
    AgentType type = new AgentType("deferred-tool-one-transaction");
    AgentId agentId = new AgentId(UUID.randomUUID());
    AtomicInteger refusals = new AtomicInteger();
    ConcurrentLinkedQueue<Narrated> heard = new ConcurrentLinkedQueue<>();
    try (EngineFixture refusing =
        new EngineFixture(
            MODEL, heard::add, backend -> new ParkRefusingBackend(backend, refusals))) {
      harness(refusing, type, Duration.ofMinutes(30), Customizer.withDefaults(), slowJob())
          .tell(agentId, "kick off the reindex");

      await()
          .atMost(Duration.ofSeconds(15))
          .untilAsserted(() -> assertThat(refusals.get()).isEqualTo(1));

      List<AgentEvent> story = refusing.story(type, agentId);
      assertThat(story).as("the turn is under way").isNotEmpty();
      assertThat(deferralsIn(story)).as("no deferral was stored").isEmpty();
      Row row = rowOf(refusing, type);
      assertThat(row.parkedAt()).as("the row is unmarked").isNull();
      assertThat(row.status()).isEqualTo("RUNNING");
      assertThat(refusing.stateOf(type, agentId))
          .asInstanceOf(type(AgentState.AwaitingActions.class))
          .satisfies(
              awaiting ->
                  assertThat(awaiting.outstanding().get(new CallId("call_1")).phase())
                      .isEqualTo(OutstandingAction.Phase.RUNNING));
      assertThat(heard).as("the turn was heard").isNotEmpty();
      assertThat(heard)
          .extracting(Narrated::event)
          .as("nothing was said about a deferral that did not happen")
          .noneMatch(Narration.CallDeferred.class::isInstance);
      assertThat(handed).as("the tool was started once").hasSize(1);

      assertThat(refusing.replies().complete(handed.peek(), ToolResult.ok(new Block.Text("done"))))
          .as("the call still waits for its report")
          .isInstanceOf(ReplyOutcome.Settled.class);
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(refusing.stateOf(type, agentId)).isInstanceOf(AgentState.Idle.class));
      assertThat(handed).as("and was never started again").hasSize(1);
      assertThat(deferralsIn(refusing.story(type, agentId)))
          .as("the finished story holds no deferral")
          .isEmpty();
    }
  }

  /**
   * A report can beat the dispatcher's own record of the deferral: the tool reports through {@code
   * Replies} and only then says it will report later. The call is already discharged, so the fold
   * writes nothing for the deferral.
   */
  @Test
  void an_answer_that_lands_first_leaves_the_park_writing_nothing() {
    AgentType type = new AgentType("deferred-tool-answer-first");
    AgentId agentId = new AgentId(UUID.randomUUID());
    ConcurrentLinkedQueue<ReplyOutcome> outcomes = new ConcurrentLinkedQueue<>();
    Tool<Job> reportingFirst =
        slowJob(
            request -> {
              outcomes.add(
                  engine
                      .replies()
                      .complete(request.replyToken(), ToolResult.ok(new Block.Text("done"))));
              return Awaited.deferred();
            });

    harness(engine, type, Duration.ofMinutes(30), Customizer.withDefaults(), reportingFirst)
        .tell(agentId, "kick off the reindex");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> assertThat(engine.stateOf(type, agentId)).isInstanceOf(AgentState.Idle.class));
    List<AgentEvent> story = engine.story(type, agentId);
    assertThat(outcomes).singleElement().isInstanceOf(ReplyOutcome.Settled.class);
    assertThat(story)
        .as("the result is on the record")
        .anyMatch(AgentEvent.ToolSucceeded.class::isInstance);
    assertThat(deferralsIn(story)).as("and the deferral that came too late is not").isEmpty();
  }

  /** The key the story's one request gave its first call. */
  private static IdempotencyKey requestedKey(List<AgentEvent> story) {
    AgentEvent.ActionsRequested requested = (AgentEvent.ActionsRequested) story.get(1);
    return ((ActionRequest.ToolCall) requested.actions().getFirst()).idempotencyKey();
  }
}
