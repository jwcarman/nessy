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
package org.jwcarman.nessy.engine.story;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/** What a listener hears on the queued door is what the story replays, retries included. */
@Tag("container")
class EventAgentStoriesQueuedTest {

  private final List<Narrated> heard = new CopyOnWriteArrayList<>();

  private static InferenceProvider callsThenAnswers() {
    return (request, narrator) ->
        request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
            ? new InferenceResult.Answer(
                List.of(new Block.Text("It is 1412 metres deep.")), Usage.of("a-model", 40, 9))
            : new InferenceResult.Actions(
                List.of(
                    new Block.Commentary("Let me look that up."),
                    new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")),
                Usage.of("a-model", 25, 6));
  }

  /** Fails once, as a provider does when it is busy, and then behaves as callsThenAnswers. */
  private static InferenceProvider busyOnceThenCallsAndAnswers() {
    AtomicBoolean failed = new AtomicBoolean();
    InferenceProvider behaving = callsThenAnswers();
    return (request, narrator) ->
        failed.compareAndSet(false, true)
            ? new InferenceResult.Fault(new Failure.Transient("busy"), Usage.of("a-model", 5, 0))
            : behaving.infer(request, narrator);
  }

  private List<Narrated> replayed(EngineFixture engine, AgentType type, AgentId agent) {
    return new EventAgentStories(engine.events(), engine.payloads())
        .of(type, agent)
        .replay(Seq.NONE, 100);
  }

  @Test
  void a_turn_with_a_tool_call_heard_live_is_what_the_replay_returns() {
    AgentType type = new AgentType("queued-story");
    AgentId agent = AgentId.random();
    NarrationListener recording = heard::add;

    try (EngineFixture engine = new EngineFixture(callsThenAnswers(), recording)) {
      engine
          .harnesses()
          .<String>create(
              type,
              String.class,
              config ->
                  config
                      .systemPrompt("You are a test assistant.")
                      .tool(StoryTurn.lookup(), t -> t.action(query -> "looked up " + query.q()))
                      .inference(in -> in.model("a-model"))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .tell(agent, "how deep is Loch Ness?");
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () ->
                  heard.stream()
                      .anyMatch(narrated -> narrated.event() instanceof Narration.Answered));

      List<Narrated> story =
          heard.stream().filter(narrated -> narrated.event() instanceof Narration.Story).toList();
      assertThat(story)
          .extracting(narrated -> narrated.event().getClass().getSimpleName())
          .containsSubsequence(
              "TurnStarted", "ActionsRequested", "CallApproved", "CallFinished", "Answered");
      assertThat(replayed(engine, type, agent)).isEqualTo(story);
    }
  }

  @Test
  void a_retried_model_call_heard_live_is_what_the_replay_returns() {
    AgentType type = new AgentType("queued-retry");
    AgentId agent = AgentId.random();
    NarrationListener recording = heard::add;

    try (EngineFixture engine = new EngineFixture(busyOnceThenCallsAndAnswers(), recording)) {
      engine
          .harnesses()
          .<String>create(
              type,
              String.class,
              config ->
                  config
                      .systemPrompt("You are a test assistant.")
                      .tool(StoryTurn.lookup(), t -> t.action(query -> "looked up " + query.q()))
                      .inference(
                          in ->
                              in.model("a-model")
                                  .retryPolicy(
                                      new RetryPolicy.FixedDelay(
                                          2, Duration.ofMillis(100), Duration.ZERO)))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .tell(agent, "how deep is Loch Ness?");
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () ->
                  heard.stream()
                      .anyMatch(narrated -> narrated.event() instanceof Narration.Answered));

      List<Narrated> story =
          heard.stream().filter(narrated -> narrated.event() instanceof Narration.Story).toList();
      assertThat(story)
          .extracting(narrated -> narrated.event().getClass().getSimpleName())
          .containsSubsequence("TurnStarted", "InferenceRetried", "ActionsRequested", "Answered");
      assertThat(replayed(engine, type, agent)).isEqualTo(story);
    }
  }
}
