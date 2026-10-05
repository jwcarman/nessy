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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A late answer finds its call by the agent and the call's key, and is told whether it changed
 * anything.
 *
 * <p>Through real Postgres, because what is being tested is the match against the stored row and
 * the fold's own refusal. Each test parks a call in a state of its own and answers it the right or
 * the wrong way; an answer that does nothing says so, and says no more than {@code Ignored}.
 */
@Tag("container")
class RepliesByKeyTest {

  record Query(String q) {}

  /** How many requests of calls the model makes before it answers. */
  private volatile int rounds = 1;

  /** How many calls each of those requests holds. */
  private volatile int asked = 1;

  /** Every call gets this id, in every request, so a repeat is a repeat. */
  private static final String CALL_ID = "call_1";

  private final InferenceProvider model =
      (request, _) -> {
        int answered =
            request.context().turns().stream().mapToInt(turn -> turn.exchanges().size()).sum();
        if (answered >= rounds * asked) {
          return new InferenceResult.Answer(List.of(new Block.Text("all done")));
        }
        List<Block.ToolCall> calls = new ArrayList<>();
        for (int i = 1; i <= asked; i++) {
          String id = asked == 1 ? CALL_ID : "call_" + i;
          calls.add(new Block.ToolCall(id, "lookup", "{\"q\":\"loch ness\"}"));
        }
        return new InferenceResult.Actions(List.copyOf(calls));
      };

  private EngineFixture engine;

  /** The harness the test last built, which is the one it tells things to. */
  private QueuedHarness<String> current;

  private final ConcurrentLinkedQueue<ApprovalRequest> asks = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<ToolCallRequest<Query>> started =
      new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<String> ran = new ConcurrentLinkedQueue<>();

  /** What the tool does when it is called; a result at once unless a test says otherwise. */
  private volatile Function<ToolCallRequest<Query>, Awaited<ToolResult>> toolBehaviour =
      request -> Awaited.ready(ToolResult.ok(new Block.Text("found")));

  /** Latches a test opened for a tool to wait on, released in teardown. */
  private final List<CountDownLatch> held = new ArrayList<>();

  @BeforeEach
  void startEngine() {
    engine = new EngineFixture(model);
  }

  @AfterEach
  void stopEngine() {
    held.forEach(CountDownLatch::countDown);
    engine.close();
  }

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
        started.add(request);
        ran.add(request.input().q());
        return toolBehaviour.apply(request);
      }
    };
  }

  /** Asks, keeps the request, and says nothing -- the whole of a deferring approver. */
  private Approver deferring() {
    return request -> {
      asks.add(request);
      return Awaited.deferred();
    };
  }

  private AgentType newType() {
    return new AgentType("replies-by-key-" + UUID.randomUUID());
  }

  private void harness(AgentType type, Approver approver, Duration requestStands) {
    current =
        engine
            .harnesses()
            .create(
                type,
                String.class,
                config ->
                    config
                        .systemPrompt("You are a test assistant.")
                        .tool(
                            lookup(),
                            t -> {
                              if (approver != null) {
                                t.action(query -> "look up " + query.q())
                                    .approver(approver, a -> a.timeout(requestStands));
                              }
                            })
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));
  }

  /** One agent of this type, told something, and its call parked at the approver. */
  private AgentId parked(AgentType type, int calls, Duration requestStands) {
    asked = calls;
    harness(type, deferring(), requestStands);
    AgentId agent = new AgentId(UUID.randomUUID());
    current.tell(agent, "what lake?");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(count(type, agent, AgentEvent.ApprovalDeferred.class)).isEqualTo(calls));
    return agent;
  }

  private AgentId parked(AgentType type) {
    return parked(type, 1, Duration.ofMinutes(30));
  }

  private <E extends AgentEvent> long count(AgentType type, AgentId agent, Class<E> kind) {
    return engine.story(type, agent).stream().filter(kind::isInstance).count();
  }

  private List<String> shape(AgentType type, AgentId agent) {
    return engine.story(type, agent).stream()
        .map(event -> event.getClass().getSimpleName())
        .toList();
  }

  private void idle(AgentType type, AgentId agent) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(engine.stateOf(type, agent).getClass().getSimpleName())
                    .isEqualTo("Idle"));
  }

  private int rowsOf(AgentId agent) {
    return engine
        .jdbc()
        .sql("SELECT count(*) FROM nessy_agent_effect WHERE agent_id = ?")
        .params(agent.value())
        .query(Integer.class)
        .single();
  }

  private ReplyOutcome approve(ApprovalRequest request, ApprovalResult result) {
    return engine
        .replies()
        .approve(request.agentType(), request.agentId(), request.idempotencyKey(), result);
  }

  private ReplyOutcome complete(ToolCallRequest<Query> request, ToolResult result) {
    return engine
        .replies()
        .complete(request.agentType(), request.agentId(), request.idempotencyKey(), result);
  }

  @Nested
  class An_answer_by_key {

    @Test
    void an_approval_by_key_runs_the_call() {
      AgentType type = newType();
      AgentId agent = parked(type);
      assertThat(ran).as("nothing ran while permission was outstanding").isEmpty();

      ReplyOutcome outcome = approve(asks.peek(), ApprovalResult.approvedBy("u_carol"));

      assertThat(outcome).isEqualTo(new ReplyOutcome.Applied());
      idle(type, agent);
      assertThat(ran).containsExactly("loch ness");
      assertThat(shape(type, agent))
          .containsExactly(
              "TurnStarted",
              "ActionsRequested",
              "ApprovalDeferred",
              "ToolApproved",
              "ToolSucceeded",
              "InferenceAnswered");
    }

    @Test
    void a_denial_by_key_is_applied() {
      AgentType type = newType();
      AgentId agent = parked(type);

      ReplyOutcome outcome =
          approve(asks.peek(), ApprovalResult.deniedBy("out of hours", "u_dave"));

      assertThat(outcome).isEqualTo(new ReplyOutcome.Applied());
      idle(type, agent);
      assertThat(ran).as("a denied call never runs").isEmpty();
      assertThat(count(type, agent, AgentEvent.ToolDenied.class)).isEqualTo(1);
    }

    @Test
    void a_result_by_key_finishes_a_deferred_tool() {
      AgentType type = newType();
      toolBehaviour = _ -> Awaited.deferred();
      harness(type, null, Duration.ofMinutes(30));
      AgentId agent = new AgentId(UUID.randomUUID());
      current.tell(agent, "what lake?");
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> assertThat(count(type, agent, AgentEvent.ToolDeferred.class)).isOne());

      ReplyOutcome outcome = complete(started.peek(), ToolResult.ok(new Block.Text("1412 metres")));

      assertThat(outcome).isEqualTo(new ReplyOutcome.Applied());
      idle(type, agent);
      assertThat(count(type, agent, AgentEvent.ToolSucceeded.class)).isOne();
    }

    @Test
    void a_denial_with_other_calls_outstanding_is_applied() {
      AgentType type = newType();
      AgentId agent = parked(type, 2, Duration.ofMinutes(30));
      assertThat(asks).hasSize(2);

      ReplyOutcome outcome = approve(asks.peek(), ApprovalResult.deniedBy("no", "u_dave"));

      assertThat(outcome)
          .as("the fold wrote a decision though it asked for nothing")
          .isEqualTo(new ReplyOutcome.Applied());
      assertThat(count(type, agent, AgentEvent.ToolDenied.class)).isOne();
      assertThat(engine.stateOf(type, agent).getClass().getSimpleName())
          .as("the other call is still outstanding")
          .isNotEqualTo("Idle");
    }
  }

  @Nested
  class A_second_answer {

    @Test
    void a_second_answer_is_ignored_and_writes_nothing() {
      AgentType type = newType();
      AgentId agent = parked(type);
      ApprovalRequest request = asks.peek();
      assertThat(approve(request, ApprovalResult.approved())).isEqualTo(new ReplyOutcome.Applied());
      idle(type, agent);
      List<AgentEvent> before = engine.story(type, agent);

      ReplyOutcome again = approve(request, ApprovalResult.deniedBy("changed my mind", "u_dave"));

      assertThat(again).isEqualTo(new ReplyOutcome.Ignored());
      assertThat(engine.story(type, agent)).isEqualTo(before);
    }

    @Test
    void two_answers_at_once_apply_once() throws Exception {
      AgentType type = newType();
      AgentId agent = parked(type);
      ApprovalRequest request = asks.peek();
      CountDownLatch go = new CountDownLatch(1);
      List<ReplyOutcome> outcomes = new ArrayList<>();
      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        List<Future<ReplyOutcome>> answers =
            List.of(
                pool.submit(
                    () -> {
                      go.await(30, TimeUnit.SECONDS);
                      return approve(request, ApprovalResult.approvedBy("u_carol"));
                    }),
                pool.submit(
                    () -> {
                      go.await(30, TimeUnit.SECONDS);
                      return approve(request, ApprovalResult.deniedBy("no", "u_dave"));
                    }));
        go.countDown();
        for (Future<ReplyOutcome> answer : answers) {
          outcomes.add(answer.get(60, TimeUnit.SECONDS));
        }
      }

      assertThat(outcomes)
          .containsExactlyInAnyOrder(new ReplyOutcome.Applied(), new ReplyOutcome.Ignored());
      idle(type, agent);
      assertThat(
              count(type, agent, AgentEvent.ToolApproved.class)
                  + count(type, agent, AgentEvent.ToolDenied.class))
          .as("one decision for the call")
          .isOne();
    }

    @Test
    void an_answer_after_the_deadline_is_ignored_and_never_applied() {
      AgentType type = newType();
      AgentId agent = parked(type, 1, Duration.ofSeconds(2));
      ApprovalRequest request = asks.peek();
      idle(type, agent);

      ReplyOutcome outcome = approve(request, ApprovalResult.approved());

      assertThat(outcome).isEqualTo(new ReplyOutcome.Ignored());
      assertThat(ran).as("an expired request authorises nothing").isEmpty();
      assertThat(count(type, agent, AgentEvent.ToolApproved.class)).isZero();
    }
  }

  @Nested
  class An_answer_that_does_not_fit {

    @Test
    void approve_for_a_running_call_is_ignored() throws Exception {
      AgentType type = newType();
      CountDownLatch release = new CountDownLatch(1);
      held.add(release);
      toolBehaviour =
          _ -> {
            try {
              release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            }
            return Awaited.ready(ToolResult.ok(new Block.Text("found")));
          };
      harness(type, _ -> Awaited.ready(ApprovalResult.approved()), Duration.ofMinutes(30));
      AgentId agent = new AgentId(UUID.randomUUID());
      current.tell(agent, "what lake?");
      await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(started).isNotEmpty());
      ToolCallRequest<Query> running = started.peek();
      List<AgentEvent> before = engine.story(type, agent);
      assertThat(rowsOf(agent)).as("the running call's row, the CallTool").isOne();

      ReplyOutcome outcome =
          engine
              .replies()
              .approve(
                  running.agentType(),
                  running.agentId(),
                  running.idempotencyKey(),
                  ApprovalResult.approved());

      assertThat(outcome).isEqualTo(new ReplyOutcome.Ignored());
      assertThat(engine.story(type, agent)).isEqualTo(before);
      assertThat(rowsOf(agent)).as("a verdict left the running row alone").isOne();
      release.countDown();
      idle(type, agent);
    }

    @Test
    void
        a_result_for_a_call_that_is_running_and_not_deferred_is_applied_and_the_tools_own_result_is_dropped()
            throws Exception {
      AgentType type = newType();
      CountDownLatch release = new CountDownLatch(1);
      held.add(release);
      toolBehaviour =
          _ -> {
            try {
              release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            }
            return Awaited.ready(ToolResult.ok(new Block.Text("the tool's own")));
          };
      harness(type, _ -> Awaited.ready(ApprovalResult.approved()), Duration.ofMinutes(30));
      AgentId agent = new AgentId(UUID.randomUUID());
      current.tell(agent, "what lake?");
      await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(started).isNotEmpty());

      ReplyOutcome outcome =
          complete(started.peek(), ToolResult.ok(new Block.Text("the reply's own")));

      assertThat(outcome).isEqualTo(new ReplyOutcome.Applied());
      assertThat(count(type, agent, AgentEvent.ToolSucceeded.class)).isOne();
      release.countDown();
      idle(type, agent);
      List<AgentEvent.ToolSucceeded> results =
          engine.story(type, agent).stream()
              .filter(AgentEvent.ToolSucceeded.class::isInstance)
              .map(AgentEvent.ToolSucceeded.class::cast)
              .toList();
      assertThat(results).as("the tool's own result wrote nothing").hasSize(1);
      assertThat(results.getFirst().rendered()).contains("the reply's own");
    }

    @Test
    void complete_for_a_call_not_yet_approved_is_ignored() {
      AgentType type = newType();
      AgentId agent = parked(type);
      ApprovalRequest request = asks.peek();

      ReplyOutcome outcome =
          engine
              .replies()
              .complete(
                  request.agentType(),
                  request.agentId(),
                  request.idempotencyKey(),
                  ToolResult.ok(new Block.Text("I said so")));

      assertThat(outcome).isEqualTo(new ReplyOutcome.Ignored());
      assertThat(ran).isEmpty();
      assertThat(count(type, agent, AgentEvent.ToolSucceeded.class)).isZero();
      assertThat(rowsOf(agent)).as("the call still waits for its approval").isOne();
      assertThat(approve(request, ApprovalResult.approved()))
          .as("and the real answer still works afterwards")
          .isEqualTo(new ReplyOutcome.Applied());
    }

    @Test
    void a_key_nobody_issued_is_ignored() {
      AgentType type = newType();
      AgentId agent = parked(type);
      ApprovalRequest request = asks.peek();
      IdempotencyKey stranger = IdempotencyKey.of(UUID.randomUUID());

      ReplyOutcome outcome =
          engine
              .replies()
              .approve(request.agentType(), request.agentId(), stranger, ApprovalResult.approved());

      assertThat(outcome).isEqualTo(new ReplyOutcome.Ignored());
      assertThat(count(type, agent, AgentEvent.ToolApproved.class)).isZero();
    }

    @Test
    void the_right_key_for_another_agent_is_ignored() {
      AgentType type = newType();
      AgentId first = parked(type);
      ApprovalRequest firstRequest = asks.peek();
      AgentId second = new AgentId(UUID.randomUUID());
      current.tell(second, "and what hill?");
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> assertThat(count(type, second, AgentEvent.ApprovalDeferred.class)).isOne());

      ReplyOutcome outcome =
          engine
              .replies()
              .approve(type, second, firstRequest.idempotencyKey(), ApprovalResult.approved());

      assertThat(outcome).isEqualTo(new ReplyOutcome.Ignored());
      assertThat(count(type, first, AgentEvent.ToolApproved.class)).isZero();
      assertThat(count(type, second, AgentEvent.ToolApproved.class)).isZero();
    }

    @Test
    void an_agent_type_not_served_here_is_ignored() {
      AgentType type = newType();
      parked(type);
      ApprovalRequest request = asks.peek();
      AgentType elsewhere = new AgentType("nobody-serves-this");

      ReplyOutcome outcome =
          engine
              .replies()
              .approve(
                  elsewhere,
                  request.agentId(),
                  request.idempotencyKey(),
                  ApprovalResult.approved());

      assertThat(outcome).isEqualTo(new ReplyOutcome.Ignored());
    }

    @Test
    void a_call_id_repeated_in_a_later_request_is_told_apart_by_its_key() {
      AgentType type = newType();
      rounds = 2;
      AgentId agent = parked(type);
      ApprovalRequest first = asks.peek();
      assertThat(approve(first, ApprovalResult.approved())).isEqualTo(new ReplyOutcome.Applied());
      await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(asks).hasSize(2));
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> assertThat(count(type, agent, AgentEvent.ApprovalDeferred.class)).isEqualTo(2));
      ApprovalRequest second = asks.stream().skip(1).findFirst().orElseThrow();
      assertThat(second.callId()).as("the model reused the call id").isEqualTo(first.callId());
      assertThat(second.idempotencyKey()).isNotEqualTo(first.idempotencyKey());
      List<AgentEvent> before = engine.story(type, agent);

      ReplyOutcome stale = approve(first, ApprovalResult.deniedBy("stale", "u_dave"));

      assertThat(stale)
          .as("the first request's key finds nothing now")
          .isEqualTo(new ReplyOutcome.Ignored());
      assertThat(engine.story(type, agent)).isEqualTo(before);
      assertThat(approve(second, ApprovalResult.approved())).isEqualTo(new ReplyOutcome.Applied());
      idle(type, agent);
      assertThat(count(type, agent, AgentEvent.ToolDenied.class)).isZero();
      assertThat(count(type, agent, AgentEvent.ToolSucceeded.class)).isEqualTo(2);
    }
  }

  @Nested
  class An_answer_inside_the_callers_transaction {

    private final String probe = "replies_by_key_probe";

    private TransactionTemplate transaction() {
      return new TransactionTemplate(new JdbcTransactionManager(engine.dataSource()));
    }

    private long probes(UUID id) {
      return engine
          .jdbc()
          .sql("SELECT count(*) FROM " + probe + " WHERE id = ?")
          .params(id)
          .query(Long.class)
          .single();
    }

    @BeforeEach
    void createProbe() {
      engine.jdbc().sql("CREATE TABLE IF NOT EXISTS " + probe + " (id uuid PRIMARY KEY)").update();
    }

    @Test
    void an_answer_inside_a_transaction_that_rolls_back_leaves_the_call_waiting() {
      AgentType type = newType();
      AgentId agent = parked(type);
      ApprovalRequest request = asks.peek();
      List<AgentEvent> before = engine.story(type, agent);
      List<ReplyOutcome> outcomes = new ArrayList<>();

      transaction()
          .executeWithoutResult(
              status -> {
                outcomes.add(approve(request, ApprovalResult.approved()));
                status.setRollbackOnly();
              });

      assertThat(outcomes).as("inside, it was applied").containsExactly(new ReplyOutcome.Applied());
      assertThat(engine.story(type, agent)).as("the story is unchanged").isEqualTo(before);
      assertThat(rowsOf(agent)).as("the row is still there").isOne();
      assertThat(approve(request, ApprovalResult.approved()))
          .as("so the call can still be answered")
          .isEqualTo(new ReplyOutcome.Applied());
    }

    @Test
    void an_answer_inside_a_transaction_that_commits_answers_with_the_callers_own_writes() {
      AgentType type = newType();
      AgentId agent = parked(type);
      ApprovalRequest request = asks.peek();
      UUID mine = UUID.randomUUID();

      transaction()
          .executeWithoutResult(
              status -> {
                engine
                    .jdbc()
                    .sql("INSERT INTO " + probe + " (id) VALUES (?)")
                    .params(mine)
                    .update();
                assertThat(approve(request, ApprovalResult.approved()))
                    .isEqualTo(new ReplyOutcome.Applied());
              });

      assertThat(probes(mine)).as("the caller's own write committed").isOne();
      idle(type, agent);
      assertThat(count(type, agent, AgentEvent.ToolApproved.class)).isOne();
      assertThat(ran).containsExactly("loch ness");
    }
  }
}
