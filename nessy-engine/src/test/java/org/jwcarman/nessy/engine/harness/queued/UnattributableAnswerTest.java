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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.inmemory.InMemoryQueuedBackend;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * An effect row that cannot be decoded names neither its turn nor its request, so the fold can only
 * attribute its answer to what the agent is on. Delivered to an agent waiting on the call, it
 * settles that call; delivered to one that is not, it settles nothing.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class UnattributableAnswerTest {

  private static final AgentType TYPE = new AgentType("unattributable");
  private static final CallId CALL = new CallId("c");

  private final CountDownLatch toolStarted = new CountDownLatch(1);
  private final CountDownLatch releaseTool = new CountDownLatch(1);
  private final CountDownLatch releaseModel = new CountDownLatch(1);
  private final AtomicInteger inferences = new AtomicInteger();

  private InMemoryQueuedBackend backend;
  private DefaultQueuedHarnessFactory factory;

  /** Asks for {@code hold} with id {@code c}, then answers; the first ask waits to be released. */
  private final InferenceProvider model =
      (request, narrator) -> {
        int nth = inferences.incrementAndGet();
        if (nth == 1) {
          await(releaseModel);
          return new InferenceResult.Actions(List.of(new Block.ToolCall("c", "hold", "{}")));
        }
        return new InferenceResult.Answer(List.of(new Block.Text("done")));
      };

  record Nothing() {}

  private Tool<Nothing> hold() {
    return new Tool<>() {
      @Override
      public Class<Nothing> inputType() {
        return Nothing.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("hold");
      }

      @Override
      public String description() {
        return "does not return until the test lets it";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Nothing> request) {
        toolStarted.countDown();
        await(releaseTool);
        return Awaited.ready(ToolResult.ok(new Block.Text("late")));
      }
    };
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(60, TimeUnit.SECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  private DefaultQueuedHarness<String> harness() {
    backend = new InMemoryQueuedBackend(new JacksonCodecFactory(JsonMapper.builder().build()));
    factory =
        DefaultQueuedHarnessFactory.of(
            config ->
                config
                    .backend(backend)
                    .provider(ProviderId.of("test"), model)
                    .inference(ProviderId.of("test"), InferenceOptions.of("m")));
    return (DefaultQueuedHarness<String>)
        factory.create(
            TYPE,
            String.class,
            config ->
                config
                    .systemPrompt("test")
                    .tool(hold(), t -> t.timeout(Duration.ofSeconds(60)))
                    .effects(e -> e.maxInFlight(4).pollInterval(Duration.ofMillis(50))));
  }

  @AfterEach
  void stop() {
    releaseModel.countDown();
    releaseTool.countDown();
    if (factory != null) {
      factory.close();
    }
  }

  private List<AgentEvent> story(AgentId agent) {
    return backend.events().readAll(TYPE, agent);
  }

  private static ConditionFactory await(String what) {
    return Awaitility.await(what).atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(5));
  }

  private void deliverUnattributable(DefaultQueuedHarness<String> harness, AgentId agent) {
    harness.deliverOutcome(
        agent,
        Optional.<TurnId>empty(),
        Optional.<Seq>empty(),
        new EffectOutcome.ToolFailed(CALL, CallFailure.FAILED, "the row could not be read"),
        "",
        List.of());
  }

  @Test
  void an_answer_that_names_neither_turn_nor_request_settles_the_call_the_agent_is_waiting_on() {
    DefaultQueuedHarness<String> harness = harness();
    AgentId agent = new AgentId(UUID.randomUUID());
    releaseModel.countDown();
    harness.tell(agent, "go");
    await("the agent is waiting on the call, which is running")
        .until(() -> toolStarted.getCount() == 0);

    deliverUnattributable(harness, agent);

    await("the turn has moved on to its next inference")
        .until(() -> inferences.get() == 2 && answered(agent));
    assertThat(story(agent))
        .as("the call is settled as a failure carrying the message")
        .anySatisfy(
            event ->
                assertThat(event)
                    .isInstanceOfSatisfying(
                        AgentEvent.ToolFailed.class,
                        failed -> {
                          assertThat(failed.callId()).isEqualTo(CALL);
                          assertThat(failed.message()).isEqualTo("the row could not be read");
                        }));
  }

  @Test
  void an_answer_that_names_neither_turn_nor_request_is_dropped_when_the_agent_is_inferring() {
    DefaultQueuedHarness<String> harness = harness();
    AgentId agent = new AgentId(UUID.randomUUID());
    harness.tell(agent, "go");
    await("the agent is inferring").until(() -> inferences.get() == 1);
    List<AgentEvent> before = story(agent);
    assertThat(before).as("only the turn's start is written").hasSize(1);

    assertThatCode(() -> deliverUnattributable(harness, agent)).doesNotThrowAnyException();

    assertThat(story(agent)).as("nothing was written").isEqualTo(before);
  }

  @Test
  void an_answer_that_names_neither_turn_nor_request_is_dropped_when_the_agent_is_idle() {
    DefaultQueuedHarness<String> harness = harness();
    AgentId agent = new AgentId(UUID.randomUUID());

    assertThatCode(() -> deliverUnattributable(harness, agent)).doesNotThrowAnyException();

    assertThat(story(agent)).as("nothing was written").isEmpty();
  }

  private boolean answered(AgentId agent) {
    return story(agent).stream().anyMatch(AgentEvent.InferenceAnswered.class::isInstance);
  }
}
