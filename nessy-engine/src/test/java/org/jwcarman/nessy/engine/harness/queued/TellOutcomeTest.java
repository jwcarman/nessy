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
package org.jwcarman.nessy.engine.harness.queued;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.BacklogPolicy;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.TellOutcome;
import org.jwcarman.nessy.api.block.Block;
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
 * What the queued door answers when an agent is told something, through real Postgres.
 *
 * <p>Each test is one agent type whose system prompt is its own name. A name starting "held" has a
 * model call that does not return until released; one starting "parked" asks for an approval nobody
 * gives, so its turn waits; anything else answers at once.
 */
@Tag("container")
@DisplayName("What telling an agent answers")
class TellOutcomeTest {

  private static final Duration PATIENT = Duration.ofSeconds(20);
  private static final Map<String, CountDownLatch> RELEASES = new ConcurrentHashMap<>();
  private static final Map<String, CountDownLatch> ENTERED = new ConcurrentHashMap<>();

  record Job(String what) {}

  private static final InferenceProvider MODEL =
      (request, _) -> {
        String story = request.systemPrompt().value();
        if (request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())) {
          return new InferenceResult.Answer(List.of(new Block.Text("all done")));
        }
        if (story.startsWith("parked")) {
          return new InferenceResult.Actions(
              List.of(new Block.ToolCall("call_1", "sign", "{\"what\":\"contract\"}")));
        }
        if (story.startsWith("held")) {
          hold(story);
        }
        return new InferenceResult.Answer(List.of(new Block.Text("all done")));
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

  private static void hold(String story) {
    ENTERED.computeIfAbsent(story, _ -> new CountDownLatch(1)).countDown();
    try {
      if (!RELEASES
          .computeIfAbsent(story, _ -> new CountDownLatch(1))
          .await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("never released: " + story);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void awaitEntered(String story) {
    ENTERED.computeIfAbsent(story, _ -> new CountDownLatch(1));
    await().atMost(PATIENT).untilAsserted(() -> assertThat(ENTERED.get(story).getCount()).isZero());
  }

  private static Tool<Job> sign() {
    return new Tool<>() {
      @Override
      public Class<Job> inputType() {
        return Job.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("sign");
      }

      @Override
      public String description() {
        return "signs";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Job> request) {
        return Awaited.ready(ToolResult.ok(new Block.Text("signed")));
      }
    };
  }

  private static QueuedHarness<String> harness(String story, BacklogPolicy<String> policy) {
    return engine
        .harnesses()
        .create(
            new AgentType(story),
            String.class,
            config ->
                config
                    .systemPrompt(story)
                    .backlogPolicy(policy)
                    .tool(
                        sign(),
                        t ->
                            t.action(job -> "sign " + job.what())
                                .approver(
                                    _ -> Awaited.deferred(),
                                    a -> a.timeout(Duration.ofMinutes(30))))
                    .inference(in -> in.model("a-model"))
                    .effects(e -> e.pollInterval(Duration.ofMillis(50))));
  }

  private static QueuedHarness<String> harness(String story) {
    return harness(story, BacklogPolicy.keepAll());
  }

  private static AgentType typeOf(String story) {
    return new AgentType(story);
  }

  private static void awaitStatus(String story, AgentId agent, Activity activity) {
    await()
        .atMost(PATIENT)
        .untilAsserted(
            () ->
                assertThat(engine.work().status(typeOf(story), agent).activity())
                    .isEqualTo(activity));
  }

  private static int queued(String story, AgentId agent) {
    return engine.work().status(typeOf(story), agent).queued();
  }

  @Nested
  @DisplayName("An agent that takes the input")
  class An_agent_that_takes_the_input {

    @Test
    void an_input_told_to_an_idle_agent_is_accepted() {
      String story = "idle-answers";
      AgentId agent = AgentId.random();

      TellOutcome outcome = harness(story).tell(agent, "go");

      assertThat(outcome).isEqualTo(new TellOutcome.Accepted());
      awaitStatus(story, agent, Activity.IDLE);
      assertThat(engine.story(typeOf(story), agent)).isNotEmpty();
    }

    @Test
    void an_input_told_during_a_turn_is_accepted_and_waits() {
      String story = "held-accepted";
      AgentId agent = AgentId.random();
      QueuedHarness<String> harness = harness(story);
      harness.tell(agent, "first");
      awaitEntered(story);

      TellOutcome outcome = harness.tell(agent, "second");

      assertThat(outcome).isEqualTo(new TellOutcome.Accepted());
      assertThat(queued(story, agent)).isOne();
    }

    @Test
    void an_input_a_policy_replaces_is_still_accepted() {
      String story = "held-replaced";
      AgentId agent = AgentId.random();
      QueuedHarness<String> harness = harness(story, BacklogPolicy.keepLatest());
      harness.tell(agent, "first");
      awaitEntered(story);

      TellOutcome second = harness.tell(agent, "second");
      TellOutcome third = harness.tell(agent, "third");

      assertThat(List.of(second, third))
          .containsExactly(new TellOutcome.Accepted(), new TellOutcome.Accepted());
      assertThat(queued(story, agent)).as("the third replaced the second").isOne();
    }
  }

  @Nested
  @DisplayName("An agent that has been terminated")
  class An_agent_that_has_been_terminated {

    @Test
    void an_input_told_to_a_terminated_agent_is_terminated_and_nothing_is_stored() {
      String story = "terminated-idle";
      AgentId agent = AgentId.random();
      QueuedHarness<String> harness = harness(story);
      harness.tell(agent, "go");
      awaitStatus(story, agent, Activity.IDLE);
      harness.terminate(agent);
      awaitStatus(story, agent, Activity.ENDED);
      List<AgentEvent> before = engine.story(typeOf(story), agent);

      TellOutcome outcome = harness.tell(agent, "too late");

      assertThat(outcome).isEqualTo(new TellOutcome.Terminated());
      assertThat(queued(story, agent)).isZero();
      assertThat(engine.story(typeOf(story), agent)).isEqualTo(before);
    }

    @Test
    void an_agent_terminated_during_a_turn_answers_terminated_at_once() {
      String story = "parked-then-terminated";
      AgentId agent = AgentId.random();
      QueuedHarness<String> harness = harness(story);
      harness.tell(agent, "go");
      awaitStatus(story, agent, Activity.WAITING);
      harness.terminate(agent);

      TellOutcome outcome = harness.tell(agent, "too late");

      assertThat(outcome).isEqualTo(new TellOutcome.Terminated());
      assertThat(engine.work().status(typeOf(story), agent).activity())
          .as("the turn has not ended, so the agent still reads as waiting")
          .isEqualTo(Activity.WAITING);
      assertThat(queued(story, agent)).isZero();
    }
  }

  @Nested
  @DisplayName("A tell inside the caller's transaction")
  class A_tell_inside_the_callers_transaction {

    private TransactionTemplate transaction() {
      return new TransactionTemplate(new JdbcTransactionManager(engine.dataSource()));
    }

    @Test
    void tell_inside_a_transaction_that_rolls_back_stores_nothing_and_was_accepted() {
      String story = "idle-rolled-back";
      AgentId agent = AgentId.random();
      QueuedHarness<String> harness = harness(story);
      List<TellOutcome> outcomes = new ArrayList<>();

      transaction()
          .executeWithoutResult(
              status -> {
                outcomes.add(harness.tell(agent, "go"));
                status.setRollbackOnly();
              });

      assertThat(outcomes).containsExactly(new TellOutcome.Accepted());
      assertThat(engine.story(typeOf(story), agent)).isEmpty();
      assertThat(queued(story, agent)).isZero();
    }

    @Test
    void tell_inside_a_transaction_that_commits_stores_the_input_and_was_accepted() {
      String story = "idle-committed";
      AgentId agent = AgentId.random();
      QueuedHarness<String> harness = harness(story);
      List<TellOutcome> outcomes = new ArrayList<>();

      transaction().executeWithoutResult(_ -> outcomes.add(harness.tell(agent, "go")));

      assertThat(outcomes).containsExactly(new TellOutcome.Accepted());
      awaitStatus(story, agent, Activity.IDLE);
      assertThat(engine.story(typeOf(story), agent)).isNotEmpty();
    }
  }
}
