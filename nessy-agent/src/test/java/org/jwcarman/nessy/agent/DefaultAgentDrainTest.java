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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.SubstrateAgentPhaseStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.NoToolsExecutor;
import org.jwcarman.nessy.agent.support.RaceOnceStore;
import org.jwcarman.nessy.agent.support.RecordingMemory;
import org.jwcarman.nessy.agent.support.RecordingObserver;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.ThrowingThenDelegatingMemory;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Versioned;

class DefaultAgentDrainTest {

  /**
   * Fix round 1, item 5: reclaims every harness this test class built (directly or via {@link
   * org.jwcarman.nessy.agent.support.TestAgents} / {@code AgentFixture}) — each now owns a live
   * delivery-worker heartbeat (harness-first spec §4) that nothing else stops.
   */
  @AfterEach
  void shutdownTrackedHarnesses() {
    HarnessTeardown.shutdownAllTracked();
  }

  @Test
  void aFailingRendererDiscardsTheObservationAndKeepsDraining() {
    // A second agent over the SAME collaborators: instances are stateless views (§3.5), so
    // completions delivered by f's executors while we drive `poisoned` are correct by design —
    // both apply against the shared store, and this test quietly proves interchangeability.
    var f = new AgentFixture();
    var poisoned =
        TestAgents.<String>wired(
            f.memory,
            f.store,
            f.backlog,
            text -> {
              if (text.startsWith("bad")) {
                throw new IllegalArgumentException("unrenderable");
              }
              return List.of(new TextBlock(text));
            },
            f.model,
            f.tools,
            f.observer,
            false,
            StalenessPolicy.never());
    f.model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("ok")), List.of(), ModelResponseId.of("response-1")));
    f.backlogQueue.add("bad-observation");
    f.backlogQueue.add("good-observation");
    poisoned.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.observer.renderFailures()).containsExactly("bad-observation");
    assertThat(f.memory.remembered())
        .contains(Message.user(List.of(new TextBlock("good-observation"))));
  }

  @Test
  void aWriterAdvancingBetweenTheIdleCheckAndThePollIsCaughtAtSaveNotHandle() {
    // The backlog's poll() doubles as an out-of-band competitor: it advances the scope from
    // Idle to AwaitingModel before handing back the observation it was asked for. With one load
    // per drain iteration, the race is caught by the save CAS (StaleStateException), never by
    // handing a non-idle phase an Observed event.
    var store =
        new SubstrateAgentPhaseStore(
            new InMemorySubstrate(), "agent", Clock.systemUTC(), TestMappers.plainlyPinned());
    var addedBack = new ArrayList<String>();
    Backlog<String> racingBacklog =
        new Backlog<>() {
          private boolean raced;

          @Override
          public void add(String observation) {
            addedBack.add(observation);
          }

          @Override
          public Optional<String> poll() {
            if (!raced) {
              raced = true;
              store.save(new Versioned<>(new AgentPhase.AwaitingModel(), store.load().version()));
            }
            return Optional.of("hello");
          }
        };
    var agent =
        TestAgents.<String>wired(
            new RecordingMemory(),
            store,
            racingBacklog,
            text -> List.of(new TextBlock(text)),
            sink -> {},
            new NoToolsExecutor(),
            new RecordingObserver(),
            false,
            StalenessPolicy.never());
    agent.drive();
    assertThat(addedBack).containsExactly("hello");
    assertThat(store.load().value()).isEqualTo(new AgentPhase.AwaitingModel());
  }

  @Test
  void anEmptyRenderDeclinesTheObservationAndKeepsDraining() {
    var f = new AgentFixture();
    f.model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("ok")), List.of(), ModelResponseId.of("response-1")));
    var poisoned =
        TestAgents.<String>wired(
            f.memory,
            f.store,
            f.backlog,
            text -> text.equals("declined") ? List.of() : List.of(new TextBlock(text)),
            f.model,
            f.tools,
            f.observer,
            false,
            StalenessPolicy.never());
    f.backlogQueue.add("declined");
    f.backlogQueue.add("good-observation");
    poisoned.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).isEmpty();
    var remembered = f.memory.remembered();
    assertThat(remembered)
        .isNotEmpty()
        .contains(Message.user(List.of(new TextBlock("good-observation"))))
        .doesNotContain(Message.user(List.of(new TextBlock("declined"))));
  }

  @Test
  void anObservationThatLosesTheRaceReturnsToTheBacklog() {
    // Competitor moves the scope off Idle just before our save; benign duplicate in memory is
    // the accepted §5.2 class — the assertion is the re-add, not memory purity.
    var inner =
        new SubstrateAgentPhaseStore(
            new InMemorySubstrate(), "agent", Clock.systemUTC(), TestMappers.plainlyPinned());
    Versioned<AgentPhase> competitorState = new Versioned<>(new AgentPhase.AwaitingModel(), 0L);
    var f = new AgentFixture(new RaceOnceStore(inner, competitorState), false);
    f.agent.tell("hello");
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).containsExactly("hello");
    assertThat(f.store.load().value()).isEqualTo(new AgentPhase.AwaitingModel());
  }

  @Test
  void aThrowingMemorySurfacesToTheCallerAndReQueuesTheObservationThenHealsOnTheNextDrive() {
    // Memory's own law 1, the shell path's half (remembrance spec §1, fix round 1 Q3): a
    // throwing remember() is NOT swallowed-and-continued here (that would hot-loop a permanently
    // broken Memory forever) — the observation goes back to the backlog, exactly like the
    // stale-state race above, and the exception surfaces to whoever called tell(). Once memory
    // heals, the next drive() (not another tell() — that would enqueue a SECOND "hello"
    // alongside the one already re-queued) drains the preserved observation exactly once.
    var store =
        new SubstrateAgentPhaseStore(
            new InMemorySubstrate(), "agent", Clock.systemUTC(), TestMappers.plainlyPinned());
    var backlogQueue = new ArrayDeque<String>();
    Backlog<String> backlog =
        new Backlog<>() {
          @Override
          public void add(String observation) {
            backlogQueue.add(observation);
          }

          @Override
          public Optional<String> poll() {
            return Optional.ofNullable(backlogQueue.poll());
          }
        };
    var recording = new RecordingMemory();
    var memory = new ThrowingThenDelegatingMemory(recording, 1); // throws once, then heals
    var agent =
        TestAgents.<String>wired(
            memory,
            store,
            backlog,
            text -> List.of(new TextBlock(text)),
            sink -> {},
            new NoToolsExecutor(),
            new RecordingObserver(),
            false,
            StalenessPolicy.never());

    assertThatThrownBy(() -> agent.tell("hello")).isInstanceOf(IllegalStateException.class);
    assertThat(backlogQueue).containsExactly("hello"); // preserved, not lost

    agent.drive(); // memory has healed — drains the preserved observation exactly once

    assertThat(backlogQueue).isEmpty();
    assertThat(recording.remembered())
        .containsExactly(Message.user(List.of(new TextBlock("hello"))));
  }

  @Test
  void drainOnIdleWiringDrainsTheNextObservationWhenTheTurnEnds() {
    var f =
        new AgentFixture(
            new SubstrateAgentPhaseStore(
                new InMemorySubstrate(), "agent", Clock.systemUTC(), TestMappers.plainlyPinned()),
            true);
    f.model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("one")), List.of(), ModelResponseId.of("response-1")));
    f.model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("two")), List.of(), ModelResponseId.of("response-1")));
    f.agent.tell("first");
    f.agent.tell("second"); // arrives mid-turn; queues
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).isEmpty();
    assertThat(f.model.callCount()).isEqualTo(2);
  }

  @Test
  void interactiveWiringLeavesTheBacklogForTheNextDrive() {
    var f =
        new AgentFixture(
            new SubstrateAgentPhaseStore(
                new InMemorySubstrate(), "agent", Clock.systemUTC(), TestMappers.plainlyPinned()),
            false);
    f.model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("one")), List.of(), ModelResponseId.of("response-1")));
    f.agent.tell("first");
    f.agent.tell("second");
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).containsExactly("second"); // waits for the client's next stream
    f.model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("two")), List.of(), ModelResponseId.of("response-1")));
    f.agent.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).isEmpty();
  }

  @Test
  void aRequeueIsNarrated() {
    var inner =
        new SubstrateAgentPhaseStore(
            new InMemorySubstrate(), "agent", Clock.systemUTC(), TestMappers.plainlyPinned());
    Versioned<AgentPhase> competitorState = new Versioned<>(new AgentPhase.AwaitingModel(), 0L);
    var f = new AgentFixture(new RaceOnceStore(inner, competitorState), false);
    f.agent.tell("hello");
    f.pump.pumpUntilQuiet();
    assertThat(f.observer.requeued()).containsExactly("hello");
  }
}
