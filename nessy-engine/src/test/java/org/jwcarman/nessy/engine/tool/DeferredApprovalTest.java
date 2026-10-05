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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
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
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * An answer that arrives long after the request, through real Postgres.
 *
 * <p>The happy path is one test. The rest are the ways an answer can arrive at the wrong moment,
 * because that is what a days-long gap guarantees will happen: twice, too late, out of order, on a
 * forged address. Every one of them must be refused out loud -- a dropped answer strands the agent
 * <em>and</em> the person who answered and believes they are done.
 */
class DeferredApprovalTest {

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
   * The provider belongs to the factory, not to a harness. It asks for the lookup on the first pass
   * and answers once the call has been settled, which is the shape a deferred approval has to
   * survive.
   */
  private static final InferenceProvider MODEL =
      (request, _) ->
          request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
              ? new InferenceResult.Answer(List.of(new Block.Text("all done")))
              : new InferenceResult.Actions(
                  List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")));

  record Query(String q) {}

  private final ConcurrentLinkedQueue<Instant> shownDeadlines = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<ApprovalRequest> handed = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<String> ran = new ConcurrentLinkedQueue<>();

  private Tool<Query> lookup() {
    return new Tool<>() {
      @Override
      public Class<Query> inputType() {
        return Query.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("lookup");
      }

      @Override
      public String description() {
        return "looks a thing up";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
        ran.add(request.input().q());
        return Awaited.ready(ToolResult.ok(new Block.Text("the answer to " + request.input().q())));
      }
    };
  }

  /** Asks once, keeps the address, says nothing -- the whole of a deferring approver. */
  private QueuedHarness<String> harness(AgentType type, Duration requestStands) {
    return harness(engine, type, requestStands, deferring());
  }

  private Approver deferring() {
    return request -> {
      handed.add(request);
      shownDeadlines.add(request.deadline());
      return Awaited.deferred();
    };
  }

  private QueuedHarness<String> harness(
      EngineFixture fixture, AgentType type, Duration requestStands, Approver approver) {
    return fixture
        .harnesses()
        .create(
            type,
            String.class,
            config ->
                config
                    .systemPrompt("You are a test assistant.")
                    .tool(
                        lookup(),
                        t ->
                            t.action(query -> "look up " + query.q())
                                .approver(approver, a -> a.timeout(requestStands)))
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

  private ApprovalRequest parkOne(AgentType type, Duration requestStands) {
    parkAgent(type, requestStands);
    return handed.peek();
  }

  private AgentId parkAgent(AgentType type, Duration requestStands) {
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, requestStands).tell(agentId, "what lake?");
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handed).isNotEmpty());
    return agentId;
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

  private static List<AgentEvent.ApprovalDeferred> deferralsIn(List<AgentEvent> story) {
    return story.stream()
        .filter(AgentEvent.ApprovalDeferred.class::isInstance)
        .map(AgentEvent.ApprovalDeferred.class::cast)
        .toList();
  }

  /**
   * The whole point: a request parked, answered later by somebody else entirely, and the turn
   * carries on from exactly where it stopped -- the tool runs, the model is asked again, the agent
   * finishes.
   */
  @Test
  void anAnswerThatArrivesLaterRunsTheCallAndFinishesTheTurn() {
    AgentType type = new AgentType("deferred-approved");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, Duration.ofMinutes(30)).tell(agentId, "what lake?");

    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handed).hasSize(1));
    assertThat(ran).as("nothing ran while permission was outstanding").isEmpty();

    assertThat(
            engine
                .replies()
                .approve(
                    handed.peek().agentType(),
                    handed.peek().agentId(),
                    handed.peek().idempotencyKey(),
                    ApprovalResult.approvedBy("u_carol")))
        .as("the agent has been told; the tool has not necessarily run yet")
        .isInstanceOf(ReplyOutcome.Applied.class);

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(type, agentId)).isEqualTo("Idle");
              assertThat(outstandingEffects(agentId)).isZero();
            });

    assertThat(ran).containsExactly("loch ness");
    List<AgentEvent> story = engine.story(type, agentId);
    assertThat(story.get(2))
        .as("the deferral is on the record before the answer that settles it")
        .isInstanceOf(AgentEvent.ApprovalDeferred.class);
    assertThat(story.get(3))
        .as("the grant carries the join to whoever actually said yes")
        .isEqualTo(
            new AgentEvent.ToolApproved(
                new Seq(4),
                new TurnId(1),
                new CallId("call_1"),
                Optional.of("u_carol"),
                JsonNodeFactory.instance.objectNode(),
                requestedKey(story)));
    assertThat(story.get(4)).isInstanceOf(AgentEvent.ToolSucceeded.class);
    assertThat(story.get(5)).isInstanceOf(AgentEvent.InferenceAnswered.class);
  }

  /**
   * The row was written when the effect was emitted and waited in the queue before anyone asked.
   * What the approver was shown is the instant the row holds the request to, not a later clock
   * reading plus the timeout.
   */
  @Test
  void an_approval_asked_after_its_row_waited_shows_the_rows_deadline() {
    AgentType type = new AgentType("deferred-shown-deadline");
    parkOne(type, Duration.ofMinutes(30));

    Instant stored =
        engine
            .jdbc()
            .sql("SELECT deadline FROM nessy_agent_effect WHERE agent_type = ?")
            .params(type.value())
            .query(OffsetDateTime.class)
            .single()
            .toInstant();

    assertThat(shownDeadlines).hasSize(1);
    assertThat(shownDeadlines.peek()).isEqualTo(stored);
  }

  /** A late denial discharges the call and never reaches the tool. */
  @Test
  void aLateDenialStopsTheCallWithoutRunningIt() {
    AgentType type = new AgentType("deferred-denied");
    ApprovalRequest request = parkOne(type, Duration.ofMinutes(30));

    assertThat(
            engine
                .replies()
                .approve(
                    request.agentType(),
                    request.agentId(),
                    request.idempotencyKey(),
                    ApprovalResult.deniedBy("out of hours", "u_dave")))
        .isInstanceOf(ReplyOutcome.Applied.class);

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(ran).as("never authorised").isEmpty());
  }

  // ---- the deferral is on the record -------------------------------------------------------

  /**
   * Recorded when it happens, not when the answer arrives: the story holds the facts, until when it
   * stands, and what was asked, before anybody has said anything.
   */
  @Test
  void a_deferred_approval_is_on_the_record_when_it_happens() {
    AgentType type = new AgentType("deferred-on-record");
    AgentId agentId = parkAgent(type, Duration.ofMinutes(30));

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(deferralsIn(engine.story(type, agentId))).hasSize(1));

    List<AgentEvent> story = engine.story(type, agentId);
    assertThat(story.get(2)).isInstanceOf(AgentEvent.ApprovalDeferred.class);
    AgentEvent.ApprovalDeferred deferred = (AgentEvent.ApprovalDeferred) story.get(2);
    assertThat(deferred.seq()).isEqualTo(new Seq(3));
    assertThat(deferred.turn()).isEqualTo(new TurnId(1));
    assertThat(deferred.callId()).isEqualTo(new CallId("call_1"));
    assertThat(deferred.idempotencyKey()).as("the call's own key").isEqualTo(requestedKey(story));
    assertThat(deferred.until())
        .as("until is the instant the row holds the request to")
        .isEqualTo(rowOf(engine, type).deadline().toInstant().truncatedTo(ChronoUnit.MICROS));
    assertThat(story)
        .as("nobody has answered yet")
        .noneMatch(AgentEvent.ToolApproved.class::isInstance);

    assertThat(deferred.facts())
        .as("an approver that added nothing leaves an empty object, and the deferral is recorded")
        .isEqualTo(JsonNodeFactory.instance.objectNode());
  }

  /** The row is marked, and it comes due once more at the deadline and not before. */
  @Test
  void a_parked_row_is_marked_and_due_at_its_deadline() {
    AgentType type = new AgentType("deferred-parked-row");
    AgentId agentId = parkAgent(type, Duration.ofMinutes(30));

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
   * event that was appended a moment earlier in the same step is rolled back with it: the story
   * does not hold a deferral the row does not know about, and nothing is narrated that did not
   * happen. The call is unaffected -- it still waits, and an answer still settles it.
   */
  @Test
  void the_event_and_the_mark_are_one_transaction() {
    AgentType type = new AgentType("deferred-one-transaction");
    AgentId agentId = new AgentId(UUID.randomUUID());
    AtomicInteger refusals = new AtomicInteger();
    ConcurrentLinkedQueue<Narrated> heard = new ConcurrentLinkedQueue<>();
    try (EngineFixture refusing =
        new EngineFixture(
            MODEL, heard::add, backend -> new ParkRefusingBackend(backend, refusals))) {
      harness(refusing, type, Duration.ofMinutes(30), deferring()).tell(agentId, "what lake?");

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
                      .isEqualTo(OutstandingAction.Phase.AWAITING_APPROVAL));
      assertThat(heard).as("the turn was heard").isNotEmpty();
      assertThat(heard)
          .extracting(Narrated::event)
          .as("nothing was said about a deferral that did not happen")
          .noneMatch(Narration.ApprovalDeferred.class::isInstance);
      assertThat(handed).as("the approver was asked once").hasSize(1);

      assertThat(
              refusing
                  .replies()
                  .approve(
                      handed.peek().agentType(),
                      handed.peek().agentId(),
                      handed.peek().idempotencyKey(),
                      ApprovalResult.approved()))
          .as("the call still waits for its answer")
          .isInstanceOf(ReplyOutcome.Applied.class);
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(refusing.stateOf(type, agentId)).isInstanceOf(AgentState.Idle.class));
      assertThat(ran).containsExactly("loch ness");
      assertThat(handed).as("and was never asked again").hasSize(1);
      assertThat(deferralsIn(refusing.story(type, agentId)))
          .as("the finished story holds no deferral")
          .isEmpty();
    }
  }

  /**
   * An answer can beat the dispatcher's own record of the deferral: the approver replies through
   * {@code Replies} and only then says it will answer later. The call has moved on, so the fold
   * writes nothing for the deferral, and the row -- settled by the answer -- is not marked.
   */
  @Test
  void an_answer_that_lands_first_leaves_the_park_writing_nothing() {
    AgentType type = new AgentType("deferred-answer-first");
    AgentId agentId = new AgentId(UUID.randomUUID());
    ConcurrentLinkedQueue<ReplyOutcome> outcomes = new ConcurrentLinkedQueue<>();
    Approver answeringFirst =
        request -> {
          outcomes.add(
              engine
                  .replies()
                  .approve(
                      request.agentType(),
                      request.agentId(),
                      request.idempotencyKey(),
                      ApprovalResult.approved()));
          return Awaited.deferred();
        };

    harness(engine, type, Duration.ofMinutes(30), answeringFirst).tell(agentId, "what lake?");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> assertThat(engine.stateOf(type, agentId)).isInstanceOf(AgentState.Idle.class));
    List<AgentEvent> story = engine.story(type, agentId);
    assertThat(outcomes).singleElement().isInstanceOf(ReplyOutcome.Applied.class);
    assertThat(story)
        .as("the decision is on the record")
        .anyMatch(AgentEvent.ToolApproved.class::isInstance);
    assertThat(deferralsIn(story)).as("and the deferral that came too late is not").isEmpty();
    assertThat(ran).containsExactly("loch ness");
  }

  /**
   * Asking again is pestering. Once the request is parked it stands until its deadline, and
   * recording it must not make the row due sooner, nor be written twice.
   */
  @Test
  void a_parked_approval_is_asked_once() {
    AgentType type = new AgentType("deferred-asked-once");
    AgentId agentId = new AgentId(UUID.randomUUID());
    AtomicInteger asked = new AtomicInteger();
    Approver counting =
        request -> {
          asked.incrementAndGet();
          return Awaited.deferred();
        };

    harness(engine, type, Duration.ofSeconds(3), counting).tell(agentId, "what lake?");

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(asked.get()).isEqualTo(1));
    await()
        .during(Duration.ofSeconds(1))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(asked.get()).as("the ask count").isEqualTo(1);
              assertThat(deferralsIn(engine.story(type, agentId)))
                  .as("one deferral on the record")
                  .hasSize(1);
            });

    await()
        .atMost(Duration.ofSeconds(25))
        .untilAsserted(
            () -> assertThat(engine.stateOf(type, agentId)).isInstanceOf(AgentState.Idle.class));
    assertThat(asked.get()).as("still once, after the deadline").isEqualTo(1);
    assertThat(deferralsIn(engine.story(type, agentId))).hasSize(1);
  }

  // ---- answers at the wrong moment ---------------------------------------------------------

  /**
   * Somebody clicks twice, or a webhook is redelivered. The second answer must not fold a second
   * outcome into a call that is already running.
   */
  @Test
  void thesameAnswerTwiceIsRefusedTheSecondTime() {
    AgentType type = new AgentType("deferred-twice");
    ApprovalRequest request = parkOne(type, Duration.ofMinutes(30));

    assertThat(
            engine
                .replies()
                .approve(
                    request.agentType(),
                    request.agentId(),
                    request.idempotencyKey(),
                    ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.Applied.class);
    assertThat(
            engine
                .replies()
                .approve(
                    request.agentType(),
                    request.agentId(),
                    request.idempotencyKey(),
                    ApprovalResult.approved()))
        .as("nothing is awaiting it any more, and saying so beats folding it twice")
        .isInstanceOf(ReplyOutcome.Ignored.class);
  }

  /**
   * The agent stopped waiting and was told so. An answer arriving now cannot be honoured -- the
   * turn it belonged to has closed -- and the person who answered deserves to be told that rather
   * than to believe it landed.
   */
  @Test
  void anAnswerAfterTheRequestExpiredIsRefused() {
    AgentType type = new AgentType("deferred-expired");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, Duration.ofSeconds(2)).tell(agentId, "what lake?");

    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handed).hasSize(1));
    ApprovalRequest request = handed.peek();

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(type, agentId)).isEqualTo("Idle");
              assertThat(outstandingEffects(agentId)).isZero();
            });

    assertThat(
            engine
                .replies()
                .approve(
                    request.agentType(),
                    request.agentId(),
                    request.idempotencyKey(),
                    ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.Ignored.class);
    assertThat(ran).as("an expired request cannot authorise anything").isEmpty();
  }

  /**
   * A verdict cannot settle a call that is past the gate, and a result cannot settle one still
   * waiting at it. The key names which effect is parked, so answering the wrong kind finds nothing
   * rather than running past the gate.
   */
  @Test
  void aToolResultCannotAnswerARequestForPermission() {
    AgentType type = new AgentType("deferred-wrong-kind");
    ApprovalRequest request = parkOne(type, Duration.ofMinutes(30));

    assertThat(
            engine
                .replies()
                .complete(
                    request.agentType(),
                    request.agentId(),
                    request.idempotencyKey(),
                    ToolResult.ok(new Block.Text("I said so"))))
        .isInstanceOf(ReplyOutcome.Ignored.class);
    assertThat(ran).isEmpty();
    assertThat(
            engine
                .replies()
                .approve(
                    request.agentType(),
                    request.agentId(),
                    request.idempotencyKey(),
                    ApprovalResult.approved()))
        .as("and the real answer still works afterwards")
        .isInstanceOf(ReplyOutcome.Applied.class);
  }

  /** The key the story's one request gave its first call. */
  private static IdempotencyKey requestedKey(List<AgentEvent> story) {
    AgentEvent.ActionsRequested requested = (AgentEvent.ActionsRequested) story.get(1);
    return ((ActionRequest.ToolCall) requested.actions().getFirst()).idempotencyKey();
  }
}
