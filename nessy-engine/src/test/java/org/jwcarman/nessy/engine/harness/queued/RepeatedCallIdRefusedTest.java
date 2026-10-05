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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * A model response that repeats a call id is not a valid response: the turn fails as it does for
 * any failed inference, and nothing the response asked for is requested, approved or run.
 */
@Tag("container")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RepeatedCallIdRefusedTest {

  private static final Duration PATIENT = Duration.ofSeconds(20);
  private static final Usage SPENT = Usage.of("a-model", 11, 7);

  record Query(String q) {}

  private static final AtomicInteger ASKED = new AtomicInteger();
  private static final AtomicInteger RUN = new AtomicInteger();

  /** The first inference of a turn repeats a call id; an input that says "again" is answered. */
  private static final InferenceProvider MODEL =
      (request, _) -> {
        boolean again =
            request.context().turns().stream()
                .anyMatch(turn -> turn.input().toString().contains("again"));
        return again
            ? new InferenceResult.Answer(List.of(new Block.Text("all done")))
            : new InferenceResult.Actions(
                List.of(
                    new Block.ToolCall("dup", "lookup", "{\"q\":\"one\"}"),
                    new Block.ToolCall("dup", "lookup", "{\"q\":\"two\"}")),
                SPENT);
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

  private static Tool<Query> lookup() {
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
        return "looks something up";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
        RUN.incrementAndGet();
        return Awaited.ready(ToolResult.ok(new Block.Text("found")));
      }
    };
  }

  @Test
  void a_turn_whose_model_repeats_a_call_id_fails_and_runs_no_tool() {
    AgentType type = new AgentType("repeats-a-call-id");
    QueuedHarness<String> harness =
        engine
            .harnesses()
            .create(
                type,
                String.class,
                config ->
                    config
                        .systemPrompt("test")
                        .tool(
                            lookup(),
                            t ->
                                t.action(query -> "look up " + query.q())
                                    .approver(
                                        request -> {
                                          ASKED.incrementAndGet();
                                          return Awaited.deferred();
                                        },
                                        a -> a.timeout(Duration.ofMinutes(30))))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));
    AgentId agent = AgentId.random();

    harness.tell(agent, "go");
    await()
        .atMost(PATIENT)
        .until(
            () ->
                engine.story(type, agent).stream()
                    .anyMatch(AgentEvent.InferenceFailed.class::isInstance));

    List<AgentEvent> story = engine.story(type, agent);
    assertThat(story).hasSize(2);
    assertThat(story.getFirst()).isInstanceOf(AgentEvent.TurnStarted.class);
    assertThat(story.getLast())
        .isInstanceOfSatisfying(
            AgentEvent.InferenceFailed.class,
            failed -> {
              assertThat(failed.failure()).isInstanceOf(Failure.Permanent.class);
              assertThat(failed.failure().reason()).contains("dup").contains("repeated a call id");
              assertThat(failed.usage()).isEqualTo(SPENT);
            });
    assertThat(story).noneMatch(AgentEvent.ActionsRequested.class::isInstance);
    assertThat(ASKED).hasValue(0);
    assertThat(RUN).hasValue(0);
    await()
        .atMost(PATIENT)
        .untilAsserted(
            () -> {
              assertThat(effectsFor(agent)).as("no effect row is left").isZero();
              assertThat(engine.work().status(type, agent).activity())
                  .isEqualTo(AgentStatus.Activity.IDLE);
            });

    harness.tell(agent, "again");
    await()
        .atMost(PATIENT)
        .until(
            () ->
                engine.story(type, agent).stream()
                    .anyMatch(AgentEvent.InferenceAnswered.class::isInstance));
  }

  private int effectsFor(AgentId agent) {
    return engine
        .jdbc()
        .sql("SELECT count(*) FROM nessy_agent_effect WHERE agent_id = ?")
        .params(agent.value())
        .query(Integer.class)
        .single();
  }
}
