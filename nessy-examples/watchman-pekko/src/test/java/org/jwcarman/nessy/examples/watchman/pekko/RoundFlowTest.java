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
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.engine.AgentActor;
import org.jwcarman.nessy.engine.AgentState;
import org.jwcarman.nessy.engine.Backlogs;
import org.jwcarman.nessy.engine.BlockingWork;
import org.jwcarman.nessy.engine.Calls;
import org.jwcarman.nessy.engine.Claims;
import org.jwcarman.nessy.engine.Coalescer;
import org.jwcarman.nessy.engine.Memories;
import org.jwcarman.nessy.engine.MicrometerTracing;
import org.jwcarman.nessy.engine.Phase;
import org.jwcarman.nessy.engine.SubstrateBacklogs;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * A whole round on Nessy's own Memory: the actors, the scripted model and the fake host, with no
 * Postgres and no Docker.
 */
@DisplayName("A watchman round")
class RoundFlowTest {

  private static final Duration PATIENCE = Duration.ofSeconds(30);

  private WatchmanActorSystem actors;
  private Memories memories;
  private String agent;

  @BeforeEach
  void start() {
    agent = "watchman-" + UUID.randomUUID();
    Substrate substrate = new InMemorySubstrate(Clock.systemUTC());
    memories = new Memories(substrate, 8000);
    Backlogs<String> backlogs = new SubstrateBacklogs<>(substrate, Coalescer.none(), String.class);
    actors =
        new WatchmanActorSystem(
            ConfigFactory.load("watchman-inmemory").resolve(),
            new ScriptedWatchmanModel(Duration.ofMillis(20)),
            new FakeRunner(),
            memories,
            backlogs,
            WatchmanObservations.RENDERER,
            MicrometerTracing.noop(),
            Clock.systemUTC(),
            new BlockingWork(),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10),
            new Claims(substrate));
    actors.start();
  }

  @AfterEach
  void stop() {
    actors.stop();
  }

  private AgentState state() {
    try {
      return actors.inspect(agent).toCompletableFuture().get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Tell the agent the way the cron does; the agent itself writes the turn once it drains. */
  private void observe(String text) {
    actors.tell(agent, new AgentActor.Observe(text, Map.of()));
  }

  /** Tool results by call id, straight out of Memory. */
  private Map<String, String> results() {
    Map<String, String> byCall = new LinkedHashMap<>();
    for (Message message : memories.everything(agent).messages()) {
      for (var block : message.content()) {
        if (block instanceof ToolResultBlock result) {
          byCall.putIfAbsent(result.toolUseId(), result.text());
        }
      }
    }
    return byCall;
  }

  private void awaitState(Class<? extends Phase> expected) {
    await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(state().phase()).isInstanceOf(expected));
  }

  private void awaitParked() {
    awaitState(Phase.WorkingTools.class);
    await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(Calls.pending(state(), "prune_images")).isPresent());
  }

  private String prune() {
    return Calls.byTool(state(), "prune_images").orElseThrow().id();
  }

  private String disk() {
    return Calls.byTool(state(), "disk_usage").orElseThrow().id();
  }

  private AgentActor.Ack answer(boolean approved, String note) throws Exception {
    return actors
        .answerApproval(agent, prune(), approved, "james", note)
        .toCompletableFuture()
        .get(15, TimeUnit.SECONDS);
  }

  @Nested
  @DisplayName("Doing the rounds")
  class DoingTheRounds {

    @Test
    void the_read_only_tools_run_and_the_one_that_needs_a_human_parks() {
      observe("It is noon. Do your rounds.");

      awaitParked();

      var working = (Phase.WorkingTools) state().phase();
      assertThat(Calls.byTool(working, "disk_usage")).isPresent();
      assertThat(Calls.byTool(working, "disk_usage").orElseThrow().settled()).isTrue();

      // MID-ROUND, recall shows NOTHING of this turn -- and that is Nessy's fold being right, not
      // a bug. An assistant message naming three tool_use ids is withheld until all three have
      // results, because a half-answered turn is a context no model will accept. The state is
      // what knows a call has settled; Memory only publishes the turn once it is whole.
      assertThat(results()).doesNotContainKey(disk());

      var prune = Calls.byTool(working, "prune_images").orElseThrow();
      assertThat(prune.settled()).isFalse();
      assertThat(prune.decided()).isFalse();
      assertThat(prune.action()).isEqualTo("docker image prune -af");
    }
  }

  @Nested
  @DisplayName("Answering the proposal")
  class AnsweringTheProposal {

    @Test
    void a_denial_settles_the_call_and_the_round_finishes() throws Exception {
      observe("It is noon. Do your rounds.");
      awaitParked();
      String prune = prune();
      String disk = disk();

      assertThat(answer(false, "not on a Friday").accepted()).isTrue();

      awaitState(Phase.Idle.class);
      // Once every call is answered the whole turn appears at once -- assistant, then results.
      assertThat(results())
          .containsEntry(prune, "denied by james: not on a Friday")
          .containsEntry(disk, "/ 91% used, 9G free");

      var messages = memories.everything(agent).messages();
      assertThat(messages).isNotEmpty();
      assertThat(messages.getLast().role()).isEqualTo(Role.ASSISTANT);
    }

    @Test
    void an_approval_runs_the_command_and_the_round_finishes() throws Exception {
      observe("It is noon. Do your rounds.");
      awaitParked();
      String prune = prune();

      assertThat(answer(true, "go on then").accepted()).isTrue();

      awaitState(Phase.Idle.class);
      assertThat(results())
          .hasEntrySatisfying(
              prune, text -> assertThat(text).contains("Total reclaimed space: 4.2GB"));
    }

    @Test
    void an_answer_for_a_call_nobody_asked_about_is_refused_rather_than_swallowed()
        throws Exception {
      observe("It is noon. Do your rounds.");
      awaitState(Phase.WorkingTools.class);

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
      observe("It is noon. Do your rounds.");
      awaitParked();
      String prune = prune();

      actors
          .answerApproval(agent, prune, false, "james", "no")
          .toCompletableFuture()
          .get(15, TimeUnit.SECONDS);
      AgentActor.Ack second =
          actors
              .answerApproval(agent, prune, true, "james", "changed my mind")
              .toCompletableFuture()
              .get(15, TimeUnit.SECONDS);

      assertThat(second.accepted()).isTrue();
      assertThat(second.detail()).isEqualTo("already answered");
      awaitState(Phase.Idle.class);
      assertThat(results()).containsEntry(prune, "denied by james: no");
    }
  }

  @Nested
  @DisplayName("A tool that throws")
  class AToolThatThrows {

    private WatchmanActorSystem throwingActors;
    private Memories throwingMemories;
    private String throwingAgent;

    @BeforeEach
    void start_a_system_whose_host_throws_on_disk_usage() {
      throwingAgent = "watchman-" + UUID.randomUUID();
      Substrate substrate = new InMemorySubstrate(Clock.systemUTC());
      throwingMemories = new Memories(substrate, 8000);
      Backlogs<String> backlogs =
          new SubstrateBacklogs<>(substrate, Coalescer.none(), String.class);
      throwingActors =
          new WatchmanActorSystem(
              ConfigFactory.load("watchman-inmemory").resolve(),
              new ScriptedWatchmanModel(Duration.ofMillis(20)),
              new ThrowingRunner(),
              throwingMemories,
              backlogs,
              WatchmanObservations.RENDERER,
              MicrometerTracing.noop(),
              Clock.systemUTC(),
              new BlockingWork(),
              Duration.ofMinutes(10),
              Duration.ofSeconds(10),
              new Claims(substrate));
      throwingActors.start();
    }

    @AfterEach
    void stop_the_throwing_system() {
      throwingActors.stop();
    }

    private AgentState throwingState() {
      try {
        return throwingActors
            .inspect(throwingAgent)
            .toCompletableFuture()
            .get(15, TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Test
    void the_thrown_call_is_remembered_as_an_error_and_its_assistant_turn_survives_recall()
        throws Exception {
      throwingActors.tell(
          throwingAgent, new AgentActor.Observe("It is noon. Do your rounds.", Map.of()));

      await()
          .atMost(PATIENCE)
          .untilAsserted(
              () -> assertThat(Calls.pending(throwingState(), "prune_images")).isPresent());

      String diskCallId = Calls.byTool(throwingState(), "disk_usage").orElseThrow().id();
      String pruneCallId = Calls.byTool(throwingState(), "prune_images").orElseThrow().id();

      AgentActor.Ack ack =
          throwingActors
              .answerApproval(throwingAgent, pruneCallId, false, "james", "not now")
              .toCompletableFuture()
              .get(15, TimeUnit.SECONDS);
      assertThat(ack.accepted()).isTrue();

      await()
          .atMost(PATIENCE)
          .untilAsserted(() -> assertThat(throwingState().phase()).isInstanceOf(Phase.Idle.class));

      List<Message> messages = throwingMemories.everything(throwingAgent).messages();

      Map<String, ToolResultBlock> resultsById = new LinkedHashMap<>();
      for (Message message : messages) {
        for (var block : message.content()) {
          if (block instanceof ToolResultBlock result) {
            resultsById.putIfAbsent(result.toolUseId(), result);
          }
        }
      }
      assertThat(resultsById).isNotEmpty();
      assertThat(resultsById).containsKey(diskCallId);
      assertThat(resultsById.get(diskCallId).isError()).isTrue();

      // The bug this guards against: a thrown tool that skips `remember` leaves its assistant
      // turn (naming this tool_use id, among the round's other calls) withheld from recall
      // forever, along with every sibling result -- silently dropping a whole turn of context.
      List<ToolUseBlock> toolUses =
          messages.stream()
              .filter(message -> message.role() == Role.ASSISTANT)
              .flatMap(message -> message.content().stream())
              .filter(ToolUseBlock.class::isInstance)
              .map(ToolUseBlock.class::cast)
              .toList();
      assertThat(toolUses).isNotEmpty();
      assertThat(toolUses).anyMatch(block -> block.call().id().equals(diskCallId));
    }
  }

  /** Throws when {@code disk_usage} shells out; every other canned command runs normally. */
  private static final class ThrowingRunner implements CommandRunner {

    private final CommandRunner delegate = new FakeRunner();

    @Override
    public Output run(List<String> argv, Duration timeout) {
      if (argv.equals(List.of("df", "-hP"))) {
        throw new IllegalStateException("host unreachable");
      }
      return delegate.run(argv, timeout);
    }
  }
}
