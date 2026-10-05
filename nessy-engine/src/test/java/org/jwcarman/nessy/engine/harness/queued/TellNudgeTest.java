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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.TellOutcome;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.effect.EffectDispatcher;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * A tell the agent takes nudges the dispatcher when it starts a turn; a tell refused because the
 * agent has been terminated nudges nothing.
 *
 * <p>Each agent is put where the test wants it with the harness's own dispatcher, which is then
 * swapped for one that only counts its nudges.
 */
@Tag("container")
class TellNudgeTest {

  private static final Duration PATIENT = Duration.ofSeconds(20);

  record Job(String what) {}

  private static final InferenceProvider MODEL =
      (request, _) -> {
        boolean answeredCalls =
            request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty());
        if (request.systemPrompt().value().startsWith("parked") && !answeredCalls) {
          return new InferenceResult.Actions(
              List.of(new Block.ToolCall("call_1", "sign", "{\"what\":\"contract\"}")));
        }
        return new InferenceResult.Answer(List.of(new Block.Text("all done")));
      };

  private static EngineFixture engine;

  private final AtomicInteger nudges = new AtomicInteger();

  @BeforeAll
  static void startEngine() {
    engine = new EngineFixture(MODEL);
  }

  @AfterAll
  static void stopEngine() {
    engine.close();
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

  private static DefaultQueuedHarness<String> harness(String story) {
    return (DefaultQueuedHarness<String>)
        engine
            .harnesses()
            .create(
                new AgentType(story),
                String.class,
                config ->
                    config
                        .systemPrompt(story)
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

  private void countNudges(String story, DefaultQueuedHarness<String> harness) {
    harness.dispatchWith(new CountingDispatcher(new AgentType(story), nudges));
    nudges.set(0);
  }

  private static void awaitStatus(String story, AgentId agent, Activity activity) {
    await()
        .atMost(PATIENT)
        .untilAsserted(
            () ->
                assertThat(engine.work().status(new AgentType(story), agent).activity())
                    .isEqualTo(activity));
  }

  @Test
  void a_tell_that_starts_a_turn_nudges_the_dispatcher() {
    String story = "nudge-accepted";
    DefaultQueuedHarness<String> harness = harness(story);
    countNudges(story, harness);

    TellOutcome outcome = harness.tell(AgentId.random(), "go");

    assertThat(outcome).isEqualTo(new TellOutcome.Accepted());
    assertThat(nudges.get()).as("the turn it started emitted an inference").isEqualTo(1);
  }

  @Test
  void a_tell_to_an_idle_terminated_agent_does_not_nudge() {
    String story = "nudge-terminated-idle";
    AgentId agent = AgentId.random();
    DefaultQueuedHarness<String> harness = harness(story);
    harness.tell(agent, "go");
    awaitStatus(story, agent, Activity.IDLE);
    harness.terminate(agent);
    awaitStatus(story, agent, Activity.ENDED);
    countNudges(story, harness);

    TellOutcome outcome = harness.tell(agent, "too late");

    assertThat(outcome).isEqualTo(new TellOutcome.Terminated());
    assertThat(nudges.get()).isZero();
  }

  @Test
  void a_tell_to_an_agent_terminated_mid_turn_does_not_nudge() {
    String story = "parked-nudge-terminated";
    AgentId agent = AgentId.random();
    DefaultQueuedHarness<String> harness = harness(story);
    harness.tell(agent, "go");
    awaitStatus(story, agent, Activity.WAITING);
    harness.terminate(agent);
    countNudges(story, harness);

    TellOutcome outcome = harness.tell(agent, "too late");

    assertThat(outcome).isEqualTo(new TellOutcome.Terminated());
    assertThat(nudges.get()).isZero();
    assertThat(engine.work().status(new AgentType(story), agent).activity())
        .isEqualTo(Activity.WAITING);
  }
}
