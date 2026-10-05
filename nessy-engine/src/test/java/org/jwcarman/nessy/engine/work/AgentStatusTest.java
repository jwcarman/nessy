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
package org.jwcarman.nessy.engine.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * What an agent is doing, on the queued door, through real Postgres.
 *
 * <p>Each test is one agent type whose system prompt is its own name; the one model reads that name
 * to know which story to play, so a single engine serves every test.
 */
@DisplayName("An agent's status on the queued door")
class AgentStatusTest {

  private static final Duration PATIENT = Duration.ofSeconds(20);
  private static final Map<String, CountDownLatch> RELEASES = new ConcurrentHashMap<>();
  private static final Map<String, CountDownLatch> ENTERED = new ConcurrentHashMap<>();
  private static final Map<String, ApprovalRequest> HANDED = new ConcurrentHashMap<>();

  record Job(String what) {}

  /**
   * Plays the story its system prompt names, on the first pass; once any call has an exchange it
   * answers. "model-held" is a model call that does not return until released.
   */
  private static final InferenceProvider MODEL =
      (request, _) -> {
        String story = request.systemPrompt().value();
        if (request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())) {
          return new InferenceResult.Answer(List.of(new Block.Text("all done")));
        }
        return switch (story) {
          case "running-and-parked" ->
              new InferenceResult.Actions(
                  List.of(
                      new Block.ToolCall("call_1", "start_job", "{\"what\":\"reindex\"}"),
                      new Block.ToolCall("call_2", "hold", "{\"what\":\"copy\"}")));
          case "all-parked", "all-parked-then-queued", "all-parked-then-terminate", "stale" ->
              new InferenceResult.Actions(
                  List.of(
                      new Block.ToolCall("call_1", "start_job", "{\"what\":\"reindex\"}"),
                      new Block.ToolCall("call_2", "start_job", "{\"what\":\"backup\"}")));
          case "approval-then-terminate" ->
              new InferenceResult.Actions(
                  List.of(new Block.ToolCall("call_1", "sign", "{\"what\":\"contract\"}")));
          case "one-parked" ->
              new InferenceResult.Actions(
                  List.of(new Block.ToolCall("call_1", "start_job", "{\"what\":\"reindex\"}")));
          default -> {
            holdAt(story);
            yield new InferenceResult.Answer(List.of(new Block.Text("all done")));
          }
        };
      };

  private static EngineFixture engine;

  @BeforeAll
  static void startEngine() {
    engine = new EngineFixture(MODEL);
  }

  @AfterAll
  static void stopEngine() {
    engine.close();
  }

  @AfterEach
  void letEverythingGo() {
    RELEASES.values().forEach(CountDownLatch::countDown);
  }

  /** Blocks until the test releases this story, after saying it got here. */
  private static void holdAt(String story) {
    ENTERED.computeIfAbsent(story, _ -> new CountDownLatch(1)).countDown();
    awaitRelease(story);
  }

  private static void awaitRelease(String story) {
    try {
      if (story.endsWith("-held")
          && !RELEASES
              .computeIfAbsent(story, _ -> new CountDownLatch(1))
              .await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("never released: " + story);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void release(String story) {
    RELEASES.computeIfAbsent(story, _ -> new CountDownLatch(1)).countDown();
  }

  private static void awaitEntered(String story) {
    ENTERED.computeIfAbsent(story, _ -> new CountDownLatch(1));
    await().atMost(PATIENT).untilAsserted(() -> assertThat(ENTERED.get(story).getCount()).isZero());
  }

  /** Says it will report back, and never does: a parked call. */
  private static Tool<Job> startJob() {
    return tool("start_job", _ -> Awaited.deferred());
  }

  /** Runs until released: a call that is working and not waiting. */
  private static Tool<Job> hold() {
    return tool(
        "hold",
        _ -> {
          holdAt("hold-held");
          return Awaited.ready(ToolResult.ok(new Block.Text("copied")));
        });
  }

  private static Tool<Job> tool(
      String name, Function<ToolCallRequest<Job>, Awaited<ToolResult>> behaviour) {
    return new Tool<>() {
      @Override
      public Class<Job> inputType() {
        return Job.class;
      }

      @Override
      public ToolName name() {
        return new ToolName(name);
      }

      @Override
      public String description() {
        return "does " + name;
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Job> request) {
        return behaviour.apply(request);
      }
    };
  }

  private QueuedHarness<String> harness(String story) {
    return engine
        .harnesses()
        .create(
            new AgentType(story),
            String.class,
            config ->
                config
                    .systemPrompt(story)
                    .tool(startJob(), t -> t.timeout(Duration.ofMinutes(30)))
                    .tool(hold(), t -> t.timeout(Duration.ofMinutes(30)))
                    .tool(
                        tool("sign", _ -> Awaited.ready(ToolResult.ok(new Block.Text("signed")))),
                        t ->
                            t.action(job -> "sign " + job.what())
                                .approver(
                                    request -> {
                                      HANDED.put(request.agentType().value(), request);
                                      return Awaited.deferred();
                                    },
                                    a -> a.timeout(Duration.ofMinutes(30))))
                    .inference(in -> in.model("a-model"))
                    .effects(e -> e.pollInterval(Duration.ofMillis(50))));
  }

  private static AgentType typeOf(String story) {
    return new AgentType(story);
  }

  private AgentStatus status(String story, AgentId agent) {
    return engine.work().status(typeOf(story), agent);
  }

  private void awaitDeferrals(String story, AgentId agent, int count) {
    await()
        .atMost(PATIENT)
        .untilAsserted(
            () ->
                assertThat(
                        engine.story(typeOf(story), agent).stream()
                            .filter(AgentEvent.ToolDeferred.class::isInstance)
                            .count())
                    .isEqualTo(count));
  }

  private void awaitStatus(String story, AgentId agent, Activity activity) {
    await()
        .atMost(PATIENT)
        .untilAsserted(() -> assertThat(status(story, agent).activity()).isEqualTo(activity));
  }

  @Nested
  @DisplayName("An agent nobody has told anything")
  class An_agent_nobody_has_told_anything {

    @Test
    void is_idle_with_nothing_queued() {
      AgentStatus status = engine.work().status(typeOf("nothing-told"), AgentId.random());

      assertThat(status)
          .isEqualTo(new AgentStatus(Activity.IDLE, 0, Optional.empty(), List.of(), 0));
    }
  }

  @Nested
  @DisplayName("An idle agent with input in its backlog")
  class An_idle_agent_with_input_in_its_backlog {

    @Test
    void an_idle_agent_with_input_queued_is_working() {
      String story = "queued-without-tell";
      AgentId agent = AgentId.random();
      harness(story);
      engine.backend().agents().ensure(typeOf(story), agent);
      engine
          .backend()
          .backlogs(TypeRef.of(String.class))
          .forAgent(typeOf(story), agent)
          .append(new BacklogItem<>("waiting", Instant.now()));

      AgentStatus status = status(story, agent);

      assertThat(status)
          .isEqualTo(new AgentStatus(Activity.WORKING, 1, Optional.empty(), List.of(), 0));
    }
  }

  @Nested
  @DisplayName("An agent in a model call")
  class An_agent_in_a_model_call {

    @Test
    void is_working_on_its_turn() {
      String story = "model-held";
      AgentId agent = AgentId.random();
      harness(story).tell(agent, "go");
      awaitEntered(story);

      AgentStatus status = status(story, agent);

      assertThat(status.activity()).isEqualTo(Activity.WORKING);
      assertThat(status.turn()).contains(new TurnId(1));
      assertThat(status.queued()).isZero();
      assertThat(status.waitingToolCalls()).isZero();
      release(story);
    }

    @Test
    void counts_what_it_was_told_behind_the_turn() {
      String story = "queued-model-held";
      AgentId agent = AgentId.random();
      QueuedHarness<String> harness = harness(story);
      harness.tell(agent, "first");
      awaitEntered(story);
      harness.tell(agent, "second");

      AgentStatus status = status(story, agent);

      assertThat(status.activity()).isEqualTo(Activity.WORKING);
      assertThat(status.queued()).isOne();
      assertThat(status.turn()).contains(new TurnId(1));
      release(story);
    }
  }

  @Nested
  @DisplayName("An agent with calls outstanding")
  class An_agent_with_calls_outstanding {

    @Test
    void is_working_while_one_call_runs_and_another_is_parked() {
      String story = "running-and-parked";
      AgentId agent = AgentId.random();
      harness(story).tell(agent, "go");
      awaitEntered("hold-held");
      awaitDeferrals(story, agent, 1);

      AgentStatus status = status(story, agent);

      assertThat(status.activity()).isEqualTo(Activity.WORKING);
      assertThat(status.waitingToolCalls()).isOne();
      release("hold-held");
    }

    @Test
    void is_waiting_when_everything_is_parked() {
      String story = "all-parked";
      AgentId agent = AgentId.random();
      harness(story).tell(agent, "go");
      awaitDeferrals(story, agent, 2);

      awaitStatus(story, agent, Activity.WAITING);
      AgentStatus status = status(story, agent);

      assertThat(status.turn()).contains(new TurnId(1));
      assertThat(status.queued()).isZero();
      assertThat(status.waitingToolCalls()).isEqualTo(2);
    }

    @Test
    void is_waiting_with_what_it_was_told_behind_the_turn_counted() {
      String story = "all-parked-then-queued";
      AgentId agent = AgentId.random();
      QueuedHarness<String> harness = harness(story);
      harness.tell(agent, "first");
      awaitDeferrals(story, agent, 2);
      harness.tell(agent, "second");

      awaitStatus(story, agent, Activity.WAITING);
      AgentStatus status = status(story, agent);

      assertThat(status.queued()).isOne();
      assertThat(status.waitingToolCalls()).isEqualTo(2);
    }

    @Test
    void counts_a_deferred_tool_call_it_is_waiting_on() {
      String story = "one-parked";
      AgentId agent = AgentId.random();
      harness(story).tell(agent, "go");
      awaitDeferrals(story, agent, 1);

      awaitStatus(story, agent, Activity.WAITING);

      assertThat(status(story, agent).waitingToolCalls()).isOne();
    }

    @Test
    void is_not_waiting_on_a_row_parked_past_its_deadline() {
      String story = "stale";
      AgentId agent = AgentId.random();
      harness(story).tell(agent, "go");
      awaitDeferrals(story, agent, 2);
      awaitStatus(story, agent, Activity.WAITING);
      Clock afterTheDeadline = Clock.fixed(Instant.now().plus(Duration.ofDays(1)), ZoneOffset.UTC);
      AgentWork later = engine.workAt(afterTheDeadline);

      AgentStatus status = later.status(typeOf(story), agent);

      assertThat(status.activity())
          .as("a row past its deadline is not waiting")
          .isEqualTo(Activity.WORKING);
      assertThat(status.waitingToolCalls()).isZero();
    }

    @Test
    void is_still_waiting_when_told_to_terminate_until_the_turn_ends() {
      String story = "all-parked-then-terminate";
      AgentId agent = AgentId.random();
      QueuedHarness<String> harness = harness(story);
      harness.tell(agent, "go");
      awaitDeferrals(story, agent, 2);
      harness.terminate(agent);
      harness.tell(agent, "too late");

      assertThat(engine.backend().agents().terminated(typeOf(story), agent))
          .as("the termination was taken")
          .isTrue();
      awaitStatus(story, agent, Activity.WAITING);
      AgentStatus status = status(story, agent);

      assertThat(status.queued()).as("ending is not an input, and a later one is refused").isZero();
      assertThat(status.turn()).contains(new TurnId(1));
    }
  }

  @Nested
  @DisplayName("An agent told to terminate while it waits on a person")
  class An_agent_told_to_terminate_while_it_waits_on_a_person {

    @Test
    void an_agent_told_to_terminate_while_waiting_ends_when_its_turn_does() {
      String story = "approval-then-terminate";
      AgentId agent = AgentId.random();
      QueuedHarness<String> harness = harness(story);
      harness.tell(agent, "go");
      await().atMost(PATIENT).untilAsserted(() -> assertThat(HANDED).containsKey(story));
      awaitStatus(story, agent, Activity.WAITING);
      harness.terminate(agent);
      ApprovalRequest request = HANDED.get(story);

      assertThat(status(story, agent).activity()).isEqualTo(Activity.WAITING);
      ReplyOutcome outcome =
          engine
              .replies()
              .approve(
                  request.agentType(),
                  request.agentId(),
                  request.idempotencyKey(),
                  ApprovalResult.approvedBy("u_carol"));

      assertThat(outcome).isInstanceOf(ReplyOutcome.Applied.class);
      awaitStatus(story, agent, Activity.ENDED);
    }
  }

  @Nested
  @DisplayName("An agent whose turn is over")
  class An_agent_whose_turn_is_over {

    @Test
    void is_idle_when_it_answered_and_finished() {
      String story = "answered";
      AgentId agent = AgentId.random();
      harness(story).tell(agent, "go");

      awaitStatus(story, agent, Activity.IDLE);

      assertThat(status(story, agent))
          .isEqualTo(new AgentStatus(Activity.IDLE, 0, Optional.empty(), List.of(), 0));
    }

    @Test
    void has_ended_when_it_was_terminated() {
      String story = "terminated";
      AgentId agent = AgentId.random();
      QueuedHarness<String> harness = harness(story);
      harness.tell(agent, "go");
      awaitStatus(story, agent, Activity.IDLE);
      harness.terminate(agent);

      awaitStatus(story, agent, Activity.ENDED);

      assertThat(status(story, agent).turn()).isEmpty();
    }

    @Test
    void is_working_until_the_turn_ends_when_told_to_terminate_mid_turn() {
      String story = "terminate-held";
      AgentId agent = AgentId.random();
      QueuedHarness<String> harness = harness(story);
      harness.tell(agent, "go");
      awaitEntered(story);
      harness.terminate(agent);

      Activity during = status(story, agent).activity();
      release(story);

      assertThat(during).isEqualTo(Activity.WORKING);
      awaitStatus(story, agent, Activity.ENDED);
    }
  }
}
