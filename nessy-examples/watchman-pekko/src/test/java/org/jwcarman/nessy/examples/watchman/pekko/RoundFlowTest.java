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
package org.jwcarman.nessy.examples.watchman.pekko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.typesafe.config.ConfigFactory;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A whole round, with no Postgres and no Docker: the actors, the scripted model and the fake host.
 *
 * <p>This is the port's equivalent of the sibling watchman's {@code RoundTest} — the proof that the
 * shape works before any of the durable machinery is involved.
 */
@DisplayName("A watchman round")
class RoundFlowTest {

  private static final Duration PATIENCE = Duration.ofSeconds(30);

  private WatchmanActorSystem actors;
  private String agent;

  @BeforeEach
  void start() {
    agent = "watchman-" + UUID.randomUUID();
    actors =
        new WatchmanActorSystem(
            ConfigFactory.load("watchman-inmemory").resolve(),
            new ScriptedModel(Duration.ofMillis(20)),
            new FakeRunner(),
            new Traces(OpenTelemetry.noop()),
            Clock.systemUTC(),
            new BlockingWork(),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10));
    actors.start();
  }

  @AfterEach
  void stop() {
    actors.stop();
  }

  private TurnState state() {
    try {
      return actors.inspect(agent).toCompletableFuture().get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void awaitState(Class<? extends TurnState> expected) {
    await().atMost(PATIENCE).untilAsserted(() -> assertThat(state()).isInstanceOf(expected));
  }

  @Nested
  @DisplayName("Doing the rounds")
  class DoingTheRounds {

    @Test
    void the_read_only_tools_run_and_the_one_that_needs_a_human_parks() {
      actors.tell(agent, new AgentActor.Observe("It is noon. Do your rounds.", java.util.Map.of()));

      awaitState(TurnState.WorkingTools.class);

      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () -> {
                var working = (TurnState.WorkingTools) state();
                assertThat(working.call("call-disk")).isPresent();
                assertThat(working.call("call-disk").orElseThrow().outcome())
                    .isEqualTo("/ 91% used, 9G free");
                assertThat(working.call("call-containers").orElseThrow().settled()).isTrue();
                // The one behind a human: asked for, not decided, not settled.
                var prune = working.call("call-prune").orElseThrow();
                assertThat(prune.settled()).isFalse();
                assertThat(prune.decided()).isFalse();
                assertThat(prune.action()).isEqualTo("docker image prune -af");
              });
    }
  }

  @Nested
  @DisplayName("Answering the proposal")
  class AnsweringTheProposal {

    @Test
    void a_denial_settles_the_call_and_the_round_finishes() throws Exception {
      actors.tell(agent, new AgentActor.Observe("It is noon. Do your rounds.", java.util.Map.of()));
      awaitState(TurnState.WorkingTools.class);
      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () -> assertThat(((TurnState.WorkingTools) state()).call("call-prune")).isPresent());

      AgentActor.Ack ack =
          actors
              .answerApproval(agent, "call-prune", false, "james", "not on a Friday")
              .toCompletableFuture()
              .get(15, TimeUnit.SECONDS);

      assertThat(ack.accepted()).isTrue();
      awaitState(TurnState.Idle.class);

      List<Turn> transcript = state().transcript();
      assertThat(transcript).isNotEmpty();
      assertThat(transcript)
          .filteredOn(Turn.ToolResult.class::isInstance)
          .extracting(turn -> ((Turn.ToolResult) turn).text())
          .anySatisfy(text -> assertThat(text).contains("denied by james: not on a Friday"));
      assertThat(transcript.getLast()).isInstanceOf(Turn.Assistant.class);
    }

    @Test
    void an_approval_runs_the_command_and_the_round_finishes() throws Exception {
      actors.tell(agent, new AgentActor.Observe("It is noon. Do your rounds.", java.util.Map.of()));
      awaitState(TurnState.WorkingTools.class);
      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () -> assertThat(((TurnState.WorkingTools) state()).call("call-prune")).isPresent());

      actors
          .answerApproval(agent, "call-prune", true, "james", "go on then")
          .toCompletableFuture()
          .get(15, TimeUnit.SECONDS);

      awaitState(TurnState.Idle.class);
      assertThat(state().transcript())
          .filteredOn(Turn.ToolResult.class::isInstance)
          .extracting(turn -> ((Turn.ToolResult) turn).text())
          .anySatisfy(text -> assertThat(text).contains("Total reclaimed space: 4.2GB"));
    }

    @Test
    void an_answer_for_a_call_nobody_asked_about_is_refused_rather_than_swallowed()
        throws Exception {
      actors.tell(agent, new AgentActor.Observe("It is noon. Do your rounds.", java.util.Map.of()));
      awaitState(TurnState.WorkingTools.class);

      AgentActor.Ack ack =
          actors
              .answerApproval(agent, "call-nonexistent", true, "james", "")
              .toCompletableFuture()
              .get(15, TimeUnit.SECONDS);

      assertThat(ack.accepted()).isFalse();
      assertThat(ack.detail()).contains("no such call");
    }

    @Test
    void a_double_click_is_idempotent() throws Exception {
      actors.tell(agent, new AgentActor.Observe("It is noon. Do your rounds.", java.util.Map.of()));
      awaitState(TurnState.WorkingTools.class);
      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () -> assertThat(((TurnState.WorkingTools) state()).call("call-prune")).isPresent());

      actors
          .answerApproval(agent, "call-prune", false, "james", "no")
          .toCompletableFuture()
          .get(15, TimeUnit.SECONDS);
      AgentActor.Ack second =
          actors
              .answerApproval(agent, "call-prune", true, "james", "changed my mind")
              .toCompletableFuture()
              .get(15, TimeUnit.SECONDS);

      assertThat(second.accepted()).isTrue();
      assertThat(second.detail()).isEqualTo("already answered");
      awaitState(TurnState.Idle.class);
      assertThat(state().transcript())
          .filteredOn(Turn.ToolResult.class::isInstance)
          .extracting(turn -> ((Turn.ToolResult) turn).text())
          .anySatisfy(text -> assertThat(text).contains("denied by james: no"));
    }
  }
}
