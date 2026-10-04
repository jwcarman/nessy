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
package org.jwcarman.nessy.engine.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryDirectBackend;
import org.jwcarman.nessy.backend.inmemory.InMemoryQueuedBackend;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.harness.queued.DefaultQueuedHarnessFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Both doors tell their listeners about a step's events only after the step has committed, and
 * never when it did not.
 *
 * <p>The commit is the return of {@code withLock}, so the locks here are ones that fail or stall on
 * their way out; the same promise is kept against a real database in {@link
 * NarrationAfterCommitJdbcTest}.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class NarrationAfterCommitTest {

  private static final Duration PATIENCE = Duration.ofSeconds(20);

  private static JacksonCodecFactory codecs() {
    return new JacksonCodecFactory(JsonMapper.builder().build());
  }

  /** Whether the history already held the turn's answer at the moment its end was heard. */
  private static NarrationListener checkingTheHistory(
      AgentEvents events, List<Boolean> readableWhenHeard) {
    return narrated -> {
      if (narrated.event() instanceof Narration.TurnEnding ended) {
        readableWhenHeard.add(
            events.readAll(narrated.agentType(), narrated.agentId()).stream()
                .anyMatch(
                    stored ->
                        stored instanceof AgentEvent.InferenceAnswered answered
                            && answered.turn().equals(ended.turn())));
      }
    };
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class The_direct_door {

    private final InMemoryDirectBackend memory = new InMemoryDirectBackend(codecs());
    private final Heard heard = new Heard();

    @Test
    void does_not_tell_a_listener_about_a_step_whose_commit_failed() {
      CommitProbe locks = new CommitProbe(memory.locks()).failTheCommitOfCall(1);
      try (DefaultDirectHarnessFactory factory =
          Doors.directFactory(new ProbedDirectBackend(memory, locks), heard)) {
        DirectHarness<String, String> harness = Doors.direct(factory);
        AgentId lost = AgentId.random();
        AgentId kept = AgentId.random();

        assertThatThrownBy(() -> harness.ask(lost, "hello"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("the commit of call 1 failed");
        harness.ask(kept, "hello");

        await().atMost(PATIENCE).until(() -> heard.kindsFor(kept).contains("Answered"));
        assertThat(heard.kindsFor(lost)).isEmpty();
      }
    }

    @Test
    void tells_a_listener_about_a_turns_end_only_once_the_history_can_show_it() {
      List<Boolean> readable = new CopyOnWriteArrayList<>();
      try (DefaultDirectHarnessFactory factory =
          Doors.directFactory(
              new ProbedDirectBackend(memory, memory.locks()),
              checkingTheHistory(memory.events(), readable))) {
        DirectHarness<String, String> harness = Doors.direct(factory);

        harness.ask(AgentId.random(), "hello");

        await().atMost(PATIENCE).until(() -> !readable.isEmpty());
        assertThat(readable).containsExactly(true);
      }
    }

    @Test
    void tells_an_agents_steps_in_commit_order_though_the_first_to_commit_is_slow_to_return() {
      CommitProbe locks = new CommitProbe(memory.locks());
      CommitProbe.Hold held = locks.holdTheReturnOfCall(2);
      try (DefaultDirectHarnessFactory factory =
          Doors.directFactory(new ProbedDirectBackend(memory, locks), heard)) {
        DirectHarness<String, String> harness = Doors.direct(factory);
        AgentId agent = AgentId.random();
        // Calls 1 and 2 are the first turn's opening and closing steps; the closing step is held
        // after its commit, so the second turn's steps commit while it is still ahead of them.
        CompletableFuture<?> first =
            CompletableFuture.supplyAsync(() -> harness.ask(agent, "first"));
        held.awaitReached();

        harness.ask(agent, "second");
        await().atMost(PATIENCE).until(() -> heard.kindsFor(agent).contains("TurnStarted"));

        assertThat(heard.kindsFor(agent))
            .as("only the first step was released; the steps behind the held one wait")
            .containsExactly("TurnStarted");
        held.release();
        first.orTimeout(PATIENCE.toSeconds(), TimeUnit.SECONDS).join();
        await().atMost(PATIENCE).until(() -> heard.kindsFor(agent).size() == 4);
        assertThat(heard.kindsFor(agent))
            .containsExactly("TurnStarted", "Answered", "TurnStarted", "Answered");
      }
    }

    @Test
    void does_not_hold_up_another_agent_behind_a_step_that_has_not_returned() {
      CommitProbe locks = new CommitProbe(memory.locks());
      CommitProbe.Hold held = locks.holdTheReturnOfCall(2);
      try (DefaultDirectHarnessFactory factory =
          Doors.directFactory(new ProbedDirectBackend(memory, locks), heard)) {
        DirectHarness<String, String> harness = Doors.direct(factory);
        AgentId slow = AgentId.random();
        AgentId quick = AgentId.random();
        CompletableFuture<?> first =
            CompletableFuture.supplyAsync(() -> harness.ask(slow, "first"));
        held.awaitReached();

        harness.ask(quick, "hello");

        await().atMost(PATIENCE).until(() -> heard.kindsFor(quick).contains("Answered"));
        assertThat(heard.kindsFor(slow)).doesNotContain("Answered");
        held.release();
        first.orTimeout(PATIENCE.toSeconds(), TimeUnit.SECONDS).join();
        await().atMost(PATIENCE).until(() -> heard.kindsFor(slow).contains("Answered"));
      }
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class The_queued_door {

    private final InMemoryQueuedBackend memory = new InMemoryQueuedBackend(codecs());
    private final Heard heard = new Heard();

    @Test
    void does_not_tell_a_listener_about_a_step_whose_commit_failed() {
      CommitProbe locks = new CommitProbe(memory.locks()).failTheCommitOfCall(1);
      try (DefaultQueuedHarnessFactory factory =
          Doors.queuedFactory(new ProbedQueuedBackend(memory, locks), heard)) {
        QueuedHarness<String> harness = Doors.queued(factory);
        AgentId lost = AgentId.random();
        AgentId kept = AgentId.random();

        // Ending an agent writes and narrates but leaves no effect behind to be performed later, so
        // what this proves is only what the failed step itself said.
        assertThatThrownBy(() -> harness.terminate(lost))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("the commit of call 1 failed");
        harness.tell(kept, "hello");

        await().atMost(PATIENCE).until(() -> heard.kindsFor(kept).contains("Answered"));
        assertThat(heard.kindsFor(lost)).isEmpty();
      }
    }

    @Test
    void tells_a_listener_about_a_turns_end_only_once_the_history_can_show_it() {
      List<Boolean> readable = new CopyOnWriteArrayList<>();
      try (DefaultQueuedHarnessFactory factory =
          Doors.queuedFactory(
              new ProbedQueuedBackend(memory, memory.locks()),
              checkingTheHistory(memory.events(), readable))) {
        QueuedHarness<String> harness = Doors.queued(factory);

        harness.tell(AgentId.random(), "hello");

        await().atMost(PATIENCE).until(() -> !readable.isEmpty());
        assertThat(readable).containsExactly(true);
      }
    }

    @Test
    void tells_an_agents_steps_in_commit_order_though_the_first_to_commit_is_slow_to_return() {
      CommitProbe locks = new CommitProbe(memory.locks());
      CommitProbe.Hold held = locks.holdTheReturnOfCall(1);
      try (DefaultQueuedHarnessFactory factory =
          Doors.queuedFactory(new ProbedQueuedBackend(memory, locks), heard)) {
        QueuedHarness<String> harness = Doors.queued(factory);
        AgentId agent = AgentId.random();
        // The step that takes the input commits and is then held. Its effect is already in the
        // outbox, so the dispatcher answers it and folds the answer in a second step that commits
        // and narrates while the first is still ahead of it.
        CompletableFuture<Void> first =
            CompletableFuture.runAsync(() -> harness.tell(agent, "first"));
        held.awaitReached();
        await()
            .atMost(PATIENCE)
            .until(
                () ->
                    memory.events().readAll(Doors.TYPE, agent).stream()
                        .anyMatch(AgentEvent.InferenceAnswered.class::isInstance));

        assertThat(heard.kindsFor(agent))
            .as("the answer is committed, but the step before it has not been released")
            .isEmpty();
        held.release();
        first.orTimeout(PATIENCE.toSeconds(), TimeUnit.SECONDS).join();

        await().atMost(PATIENCE).until(() -> heard.kindsFor(agent).contains("Answered"));
        assertThat(heard.kindsFor(agent)).containsExactly("TurnStarted", "Thinking", "Answered");
      }
    }

    @Test
    void tells_a_story_event_with_its_stored_position_and_a_live_signal_with_none() {
      try (DefaultQueuedHarnessFactory factory =
          Doors.queuedFactory(new ProbedQueuedBackend(memory, memory.locks()), heard)) {
        QueuedHarness<String> harness = Doors.queued(factory);
        AgentId agent = AgentId.random();

        harness.tell(agent, "hello");

        await().atMost(PATIENCE).until(() -> heard.kindsFor(agent).contains("Answered"));
        List<Heard.Line> lines = heard.forAgent(agent).toList();
        assertThat(lines)
            .extracting(Heard.Line::kind)
            .containsExactly("TurnStarted", "Thinking", "Answered");
        assertThat(lines.get(1).position()).as("a live signal has no place in the story").isEmpty();
        for (Heard.Line story : List.of(lines.get(0), lines.get(2))) {
          Narrated.Position position = story.position().orElseThrow();
          assertThat(memory.events().writtenAt(Doors.TYPE, agent, position.seq()))
              .isEqualTo(position.at());
        }
        assertThat(
                memory.events().readAll(Doors.TYPE, agent).stream().map(AgentEvent::seq).toList())
            .contains(
                lines.get(0).position().orElseThrow().seq(),
                lines.get(2).position().orElseThrow().seq());
      }
    }

    @Test
    void does_not_hold_up_another_agent_behind_a_step_that_has_not_returned() {
      CommitProbe locks = new CommitProbe(memory.locks());
      CommitProbe.Hold held = locks.holdTheReturnOfCall(1);
      try (DefaultQueuedHarnessFactory factory =
          Doors.queuedFactory(new ProbedQueuedBackend(memory, locks), heard)) {
        QueuedHarness<String> harness = Doors.queued(factory);
        AgentId slow = AgentId.random();
        AgentId quick = AgentId.random();
        CompletableFuture<Void> first =
            CompletableFuture.runAsync(() -> harness.tell(slow, "first"));
        held.awaitReached();

        harness.tell(quick, "hello");

        await().atMost(PATIENCE).until(() -> heard.kindsFor(quick).contains("Answered"));
        assertThat(heard.kindsFor(slow)).isEmpty();
        held.release();
        first.orTimeout(PATIENCE.toSeconds(), TimeUnit.SECONDS).join();
        await().atMost(PATIENCE).until(() -> heard.kindsFor(slow).contains("Answered"));
      }
    }
  }
}
