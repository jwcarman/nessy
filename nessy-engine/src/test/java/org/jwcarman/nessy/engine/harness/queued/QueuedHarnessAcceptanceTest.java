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

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.effect.EffectDispatcher;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * Delivering an outcome says whether the fold took it, and the dispatcher is told to look for work
 * exactly when it was before.
 *
 * <p>Outcomes are delivered by hand to agents the test has put in a known place, through real
 * Postgres. The poll is ten minutes and the dispatcher the harness holds is swapped, once the agent
 * is where the test wants it, for one that counts its nudges: the effects an accepted outcome emits
 * are then written and never run, so the story and the count are both settled the moment {@code
 * deliverOutcome} returns.
 */
@Tag("container")
class QueuedHarnessAcceptanceTest {

  /** How many calls the model asks for on its first pass; none means it simply answers. */
  private volatile int calls = 0;

  /**
   * The latches the model waits on before it answers, so an agent stays mid-inference. Every one is
   * kept and released in teardown, so no model thread stays blocked behind a test that took two.
   */
  private final List<CountDownLatch> gates = new CopyOnWriteArrayList<>();

  private final AtomicInteger nudges = new AtomicInteger();

  private final InferenceProvider model =
      (request, _) -> {
        for (CountDownLatch gate : gates) {
          hold(gate);
        }
        boolean answeredCalls =
            request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty());
        if (calls == 0 || answeredCalls) {
          return new InferenceResult.Answer(List.of(new Block.Text("all done")));
        }
        List<Block.ToolCall> asked = new ArrayList<>();
        for (int i = 1; i <= calls; i++) {
          asked.add(new Block.ToolCall("c" + i, "lookup", "{\"q\":\"loch ness\"}"));
        }
        return new InferenceResult.Actions(List.copyOf(asked));
      };

  private final EngineFixture engine = new EngineFixture(model);

  private int agentTypes;

  @AfterEach
  void stop() {
    gates.forEach(CountDownLatch::countDown);
    engine.close();
  }

  record Query(String q) {}

  private static void hold(CountDownLatch latch) {
    try {
      latch.await(60, TimeUnit.SECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
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
        return Awaited.ready(ToolResult.ok(new Block.Text("found")));
      }
    };
  }

  /** The approver that says it will answer later, which is what parks a call. */
  private Approver deferring() {
    return _ -> Awaited.deferred();
  }

  /** A dispatcher that only counts the nudges it is given. */
  private static final class CountingDispatcher extends EffectDispatcher {

    private final AtomicInteger nudges;

    CountingDispatcher(AgentType type, AtomicInteger nudges) {
      super(type, null, null, null, Clock.systemUTC(), null, null, Duration.ofMinutes(10), 1);
      this.nudges = nudges;
    }

    @Override
    public void nudge() {
      nudges.incrementAndGet();
    }
  }

  /** One agent, put where the test wants it, on a harness of its own type. */
  private final class Placed {

    private final AgentType type = new AgentType("acceptance-" + (++agentTypes));
    private final DefaultQueuedHarness<String> harness = harness(type);
    private final AgentId agent = new AgentId(UUID.randomUUID());

    List<AgentEvent> story() {
      return engine.story(type, agent);
    }

    List<String> shape() {
      return story().stream().map(event -> event.getClass().getSimpleName()).toList();
    }

    Seq request() {
      return story().stream()
          .filter(AgentEvent.ActionsRequested.class::isInstance)
          .map(AgentEvent::seq)
          .findFirst()
          .orElseThrow();
    }

    /** From here on the harness's dispatcher is nudged into a count and does nothing else. */
    void countNudges() {
      harness.dispatchWith(new CountingDispatcher(type, nudges));
      nudges.set(0);
    }

    boolean deliver(Optional<TurnId> turn, Optional<Seq> request, EffectOutcome outcome) {
      return harness.deliverOutcome(agent, turn, request, outcome, "", List.of());
    }

    /** Delivers to the turn and request the agent is on. */
    boolean deliver(EffectOutcome outcome) {
      return deliver(Optional.of(new TurnId(1)), Optional.of(request()), outcome);
    }
  }

  private DefaultQueuedHarness<String> harness(AgentType type) {
    return (DefaultQueuedHarness<String>)
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
                            t ->
                                t.action(query -> "look up " + query.q())
                                    .approver(deferring(), a -> a.timeout(Duration.ofMinutes(30))))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMinutes(10))));
  }

  /** An agent waiting on {@code asked} calls, each of them deferred at the approver. */
  private Placed awaitingApproval(int asked) {
    calls = asked;
    Placed placed = new Placed();
    placed.harness.tell(placed.agent, "what lake?");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(
                        placed.story().stream()
                            .filter(AgentEvent.ApprovalDeferred.class::isInstance)
                            .count())
                    .isEqualTo(asked));
    placed.countNudges();
    return placed;
  }

  /** An agent that has finished its turn and is idle. */
  private Placed idle() {
    calls = 0;
    Placed placed = new Placed();
    placed.harness.tell(placed.agent, "what lake?");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(placed.shape()).hasSize(2));
    placed.countNudges();
    return placed;
  }

  /** An agent mid-inference: the model has been asked and is held. */
  private Placed inferring() {
    calls = 0;
    gates.add(new CountDownLatch(1));
    Placed placed = new Placed();
    placed.harness.tell(placed.agent, "what lake?");
    assertThat(placed.shape()).containsExactly("TurnStarted");
    placed.countNudges();
    return placed;
  }

  private static EffectOutcome approved(String call) {
    return new EffectOutcome.ToolApproved(new CallId(call), Optional.of("u_carol"));
  }

  private static EffectOutcome denied(String call) {
    return new EffectOutcome.ToolDenied(new CallId(call), "out of hours", Optional.of("u_dave"));
  }

  private EffectOutcome answered(Placed placed) {
    return new EffectOutcome.InferenceAnswered(
        engine.ref(placed.agent, List.of(new Block.Text("by hand"))),
        false,
        Usage.unreported(),
        Optional.empty());
  }

  private static final Optional<TurnId> TURN_ONE = Optional.of(new TurnId(1));

  @Test
  void an_answer_the_fold_takes_is_accepted() {
    Placed placed = awaitingApproval(1);

    boolean accepted = placed.deliver(approved("c1"));

    assertThat(accepted).isTrue();
    assertThat(placed.shape())
        .containsExactly("TurnStarted", "ActionsRequested", "ApprovalDeferred", "ToolApproved");
  }

  @Test
  void a_denial_with_other_calls_outstanding_is_accepted_though_it_asks_for_nothing() {
    Placed placed = awaitingApproval(2);

    boolean accepted = placed.deliver(denied("c1"));

    assertThat(accepted).isTrue();
    assertThat(placed.shape())
        .containsExactly(
            "TurnStarted",
            "ActionsRequested",
            "ApprovalDeferred",
            "ApprovalDeferred",
            "ToolDenied");
  }

  @Test
  void a_result_that_ends_the_turn_is_accepted() {
    Placed placed = inferring();

    boolean accepted = placed.deliver(TURN_ONE, Optional.empty(), answered(placed));

    assertThat(accepted).isTrue();
    assertThat(placed.shape()).containsExactly("TurnStarted", "InferenceAnswered");
  }

  @Test
  void a_second_answer_for_the_same_call_is_not_accepted_and_writes_nothing() {
    Placed placed = awaitingApproval(2);
    assertThat(placed.deliver(denied("c1"))).isTrue();
    List<AgentEvent> before = placed.story();

    boolean again = placed.deliver(denied("c1"));

    assertThat(again).isFalse();
    assertThat(placed.story()).isEqualTo(before);
  }

  @Test
  void an_answer_for_another_turn_is_not_accepted() {
    Placed placed = awaitingApproval(2);
    List<AgentEvent> before = placed.story();

    boolean accepted =
        placed.deliver(Optional.of(new TurnId(99)), Optional.of(placed.request()), denied("c1"));

    assertThat(accepted).isFalse();
    assertThat(placed.story()).isEqualTo(before);
  }

  @Test
  void an_answer_for_another_request_is_not_accepted() {
    Placed placed = awaitingApproval(2);
    List<AgentEvent> before = placed.story();

    boolean accepted = placed.deliver(TURN_ONE, Optional.of(new Seq(99)), denied("c1"));

    assertThat(accepted).isFalse();
    assertThat(placed.story()).isEqualTo(before);
  }

  /** An idle agent is on no turn, so an outcome that names none has nothing to settle. */
  @Test
  void an_outcome_that_names_no_turn_is_not_accepted() {
    Placed placed = idle();
    List<AgentEvent> before = placed.story();

    boolean accepted = placed.deliver(Optional.empty(), Optional.empty(), denied("c1"));

    assertThat(accepted).isFalse();
    assertThat(placed.story()).isEqualTo(before);
  }

  /** On a turn but waiting on no request, an outcome that names none has no call to settle. */
  @Test
  void an_answer_that_names_no_request_to_an_agent_waiting_on_none_is_not_accepted() {
    Placed placed = inferring();
    List<AgentEvent> before = placed.story();

    boolean accepted = placed.deliver(TURN_ONE, Optional.empty(), denied("c1"));

    assertThat(accepted).isFalse();
    assertThat(placed.story()).isEqualTo(before);
  }

  /**
   * The nudge follows the effects a step emitted and the turn it started, and nothing else: not
   * whether the fold accepted the outcome. An accepted denial that asks for nothing does not nudge,
   * and a result that ends a turn nudges only when an input was waiting to become the next one.
   */
  @Test
  void the_dispatcher_is_nudged_as_before() {
    Placed approval = awaitingApproval(1);
    approval.deliver(approved("c1"));
    assertThat(nudges.get()).as("an approval that emits a call to run").isEqualTo(1);

    Placed denial = awaitingApproval(2);
    denial.deliver(denied("c1"));
    assertThat(nudges.get()).as("an accepted denial that asks for nothing").isZero();

    denial.deliver(denied("c1"));
    assertThat(nudges.get()).as("a second answer for the same call").isZero();

    Placed anotherTurn = awaitingApproval(2);
    anotherTurn.deliver(
        Optional.of(new TurnId(99)), Optional.of(anotherTurn.request()), denied("c1"));
    assertThat(nudges.get()).as("an answer for another turn").isZero();

    Placed anotherRequest = awaitingApproval(2);
    anotherRequest.deliver(TURN_ONE, Optional.of(new Seq(99)), denied("c1"));
    assertThat(nudges.get()).as("an answer for another request").isZero();

    Placed noTurn = idle();
    noTurn.deliver(Optional.empty(), Optional.empty(), denied("c1"));
    assertThat(nudges.get()).as("an outcome that names no turn").isZero();

    Placed ending = inferring();
    ending.deliver(TURN_ONE, Optional.empty(), answered(ending));
    assertThat(nudges.get()).as("a result that ends the turn, nothing waiting").isZero();

    Placed waiting = inferring();
    waiting.harness.tell(waiting.agent, "and another thing");
    nudges.set(0);
    waiting.deliver(TURN_ONE, Optional.empty(), answered(waiting));
    assertThat(nudges.get()).as("a result that ends the turn, an input waiting").isEqualTo(1);
    assertThat(waiting.shape()).containsExactly("TurnStarted", "InferenceAnswered", "TurnStarted");
  }
}
