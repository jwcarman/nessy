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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.inmemory.InMemoryQueuedBackend;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tool that overruns its deadline is given up on at the deadline and its failure delivered; the
 * model, asking again for a call with the SAME id, gets a second request. When the first attempt
 * finally returns, its outcome must not be taken for the second request's call.
 *
 * <p>Real dispatcher, in-memory backend, real (short) deadline. The latches decide the order of
 * everything that matters; the only wall-clock dependence is that the tool's deadline passes.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RepeatedCallIdLateOutcomeTest {

  private static final AgentType TYPE = new AgentType("late-outcome");
  private static final Duration TOOL_DEADLINE = Duration.ofMillis(400);

  private final CountDownLatch secondStarted = new CountDownLatch(1);
  private final CountDownLatch releaseFirst = new CountDownLatch(1);
  private final CountDownLatch releaseSecond = new CountDownLatch(1);
  private final AtomicInteger invocations = new AtomicInteger();
  private final AtomicInteger inferences = new AtomicInteger();

  private InMemoryQueuedBackend backend;
  private DefaultQueuedHarnessFactory factory;

  private final Logger harnessLog = (Logger) LoggerFactory.getLogger(DefaultQueuedHarness.class);
  private final Ignored ignored = new Ignored();

  private volatile Duration modelLatency = Duration.ZERO;

  /** Asks for {@code slow} with id {@code c}, twice, then answers; later asks take a moment. */
  private final InferenceProvider model =
      (request, narrator) -> {
        int nth = inferences.incrementAndGet();
        if (nth > 1) {
          pause(modelLatency);
        }
        return nth <= 2
            ? new InferenceResult.Actions(List.of(new Block.ToolCall("c", "slow", "{}")))
            : new InferenceResult.Answer(List.of(new Block.Text("done")));
      };

  private static void pause(Duration time) {
    try {
      Thread.sleep(time);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  record Nothing() {}

  private Tool<Nothing> slow() {
    return new Tool<>() {
      @Override
      public Class<Nothing> inputType() {
        return Nothing.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("slow");
      }

      @Override
      public String description() {
        return "outlasts its deadline the first time";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Nothing> request) {
        int mine = invocations.incrementAndGet();
        try {
          if (mine == 1) {
            releaseFirst.await(60, TimeUnit.SECONDS);
            return Awaited.ready(ToolResult.ok(new Block.Text("FIRST")));
          }
          secondStarted.countDown();
          releaseSecond.await(60, TimeUnit.SECONDS);
          return Awaited.ready(ToolResult.ok(new Block.Text("SECOND")));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return Awaited.ready(new ToolResult.Failure("interrupted: invocation " + mine));
        }
      }
    };
  }

  @AfterEach
  void stop() {
    harnessLog.detachAppender(ignored);
    harnessLog.setLevel(null);
    releaseFirst.countDown();
    releaseSecond.countDown();
    if (factory != null) {
      factory.close();
    }
  }

  private List<AgentEvent> story(AgentId agent) {
    return backend.events().readAll(TYPE, agent);
  }

  private String resultText(Payloads payloads, AgentEvent.ToolSucceeded succeeded) {
    return switch (payloads.get(succeeded.result())) {
      case Payloads.Resolved.Found found ->
          found.content().stream()
              .filter(Block.Text.class::isInstance)
              .map(Block.Text.class::cast)
              .map(Block.Text::text)
              .reduce("", String::concat);
      case Payloads.Resolved.Missing _ -> "<missing>";
    };
  }

  private String printed(AgentId agent) {
    Payloads payloads = backend.payloads().forAgent(agent);
    StringBuilder out = new StringBuilder();
    for (AgentEvent event : story(agent)) {
      out.append(event.seq().value()).append(' ').append(event.getClass().getSimpleName());
      switch (event) {
        case AgentEvent.ToolSucceeded s ->
            out.append(" call=")
                .append(s.callId().value())
                .append(" line='")
                .append(s.rendered())
                .append("' payload='")
                .append(resultText(payloads, s))
                .append("'");
        case AgentEvent.ToolFailed f ->
            out.append(" call=")
                .append(f.callId().value())
                .append(" message='")
                .append(f.message())
                .append("'");
        case AgentEvent.ActionsRequested r -> out.append(" actions=").append(r.actions());
        default -> {}
      }
      out.append('\n');
    }
    return out.toString();
  }

  @Test
  void with_a_model_that_answers_instantly_a_late_outcome_is_still_not_taken_for_a_later_request() {
    scenario(Duration.ZERO);
  }

  @Test
  void with_a_model_that_takes_300_ms_a_late_outcome_is_not_taken_for_a_later_request() {
    scenario(Duration.ofMillis(300));
  }

  private void scenario(Duration latency) {
    modelLatency = latency;
    harnessLog.setLevel(Level.DEBUG);
    harnessLog.addAppender(ignored);
    ignored.start();
    backend = new InMemoryQueuedBackend(new JacksonCodecFactory(JsonMapper.builder().build()));
    factory =
        DefaultQueuedHarnessFactory.of(
            config ->
                config
                    .backend(backend)
                    .provider(ProviderId.of("test"), model)
                    .inference(ProviderId.of("test"), InferenceOptions.of("m")));
    QueuedHarness<String> harness =
        factory.create(
            TYPE,
            String.class,
            config ->
                config
                    .systemPrompt("test")
                    .tool(slow(), t -> t.timeout(TOOL_DEADLINE))
                    .effects(e -> e.maxInFlight(4).pollInterval(Duration.ofMillis(50))));
    AgentId agent = new AgentId(UUID.randomUUID());

    harness.tell(agent, "go");

    // The first invocation is held past its deadline: the dispatcher gives up on it, the model asks
    // again with the same id, and the second invocation starts.
    await("the second request's call is running").until(() -> secondStarted.getCount() == 0);

    // Only now does the first invocation return. Its answer reaches the agent while the second
    // request's call is the one being waited on, and the next step does not happen until the agent
    // has said what it made of it: an answer taken for the second request's call would be recorded
    // in the story, and one that is not is logged as ignored.
    releaseFirst.countDown();
    await("the late answer has been dealt with")
        .until(() -> ignored.heard() || backendRecordedASecondDischarge(agent));
    releaseSecond.countDown();
    await("the turn has finished").until(() -> answered(agent));

    String finalStory = printed(agent);
    Payloads payloads = backend.payloads().forAgent(agent);
    List<AgentEvent> events = story(agent);
    long secondRequestSeq =
        events.stream()
            .filter(AgentEvent.ActionsRequested.class::isInstance)
            .skip(1)
            .findFirst()
            .map(e -> e.seq().value())
            .orElseThrow();
    List<AgentEvent> dischargesOfSecondRequest =
        events.stream()
            .filter(e -> e.seq().value() > secondRequestSeq)
            .filter(
                e -> e instanceof AgentEvent.ToolSucceeded || e instanceof AgentEvent.ToolFailed)
            .toList();
    assertThat(dischargesOfSecondRequest)
        .as("the second request's call is discharged exactly once; story:%n%s", finalStory)
        .hasSize(1);
    assertThat(dischargesOfSecondRequest.getFirst())
        .as("and by the second request's own result, SECOND; story:%n%s", finalStory)
        .isInstanceOfSatisfying(
            AgentEvent.ToolSucceeded.class,
            recorded -> {
              assertThat(resultText(payloads, recorded)).isEqualTo("SECOND");
              assertThat(recorded.rendered()).contains("SECOND");
            });
  }

  /** What the agent's story says once the second request has been discharged by anything at all. */
  private boolean backendRecordedASecondDischarge(AgentId agent) {
    return story(agent).stream()
            .filter(
                e -> e instanceof AgentEvent.ToolSucceeded || e instanceof AgentEvent.ToolFailed)
            .count()
        > 1;
  }

  private boolean answered(AgentId agent) {
    return story(agent).stream().anyMatch(AgentEvent.InferenceAnswered.class::isInstance);
  }

  private static ConditionFactory await(String what) {
    return Awaitility.await(what).atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(5));
  }

  /** Remembers whether the agent has said it ignored a tool call's answer. */
  private static final class Ignored extends AppenderBase<ILoggingEvent> {

    private final AtomicBoolean heard = new AtomicBoolean();

    @Override
    protected void append(ILoggingEvent event) {
      if (event.getFormattedMessage().contains("ignoring CompleteToolCall")) {
        heard.set(true);
      }
    }

    boolean heard() {
      return heard.get();
    }
  }
}
