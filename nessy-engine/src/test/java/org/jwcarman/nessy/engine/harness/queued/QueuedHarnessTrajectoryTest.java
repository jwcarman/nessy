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

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.TurnStats;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.agent.Agents;
import org.jwcarman.nessy.backend.backlog.Backlogs;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.effect.Effects;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.core.TurnTally;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/** A turn's row commits with the event that ended it, against a real PostgreSQL. */
@Tag("container")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class QueuedHarnessTrajectoryTest {

  private static final AgentType TYPE = new AgentType("trajectory");
  private static final Duration PATIENCE = Duration.ofSeconds(30);

  private static final InferenceProvider ANSWERS =
      (request, narrator) ->
          new InferenceResult.Answer(
              List.of(new Block.Text("an answer")), Usage.unreported("a-model"));

  private EngineFixture engine;

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  private QueuedHarness<String> harness() {
    return engine
        .harnesses()
        .create(
            TYPE,
            String.class,
            c ->
                c.systemPrompt("You are terse.")
                    .effects(e -> e.pollInterval(Duration.ofMillis(50))));
  }

  private AgentTurn tellAndAwaitItsRow(QueuedHarness<String> harness, AgentId agent) {
    harness.tell(agent, "hello");
    await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(engine.backend().turns().of(TYPE, agent)).hasSize(1));
    return engine.backend().turns().of(TYPE, agent).getFirst();
  }

  @Test
  void a_told_turn_leaves_one_row_bounded_by_its_events() {
    engine = new EngineFixture(ANSWERS);
    AgentId agent = AgentId.random();

    AgentTurn row = tellAndAwaitItsRow(harness(), agent);

    List<AgentEvent> story = engine.story(TYPE, agent);
    assertThat(story.getFirst()).isInstanceOf(AgentEvent.TurnStarted.class);
    assertThat(row.turn().value()).isEqualTo(story.getFirst().seq().value());
    assertThat(row.endingSeq()).isEqualTo(story.getLast().seq());
    assertThat(story)
        .allSatisfy(
            e ->
                assertThat(e.seq().value()).isBetween(row.turn().value(), row.endingSeq().value()));
    assertThat(row.outcome()).isEqualTo(TurnOutcome.ANSWERED);
  }

  @Test
  void the_rows_counts_agree_with_the_tally_of_the_same_turn() {
    engine = new EngineFixture(ANSWERS);
    AgentId agent = AgentId.random();

    AgentTurn row = tellAndAwaitItsRow(harness(), agent);

    TurnStats stats = TurnTally.of(engine.story(TYPE, agent), row.turn());
    assertThat(row.inferenceCalls()).isEqualTo(stats.modelCalls());
    assertThat(row.toolCalls()).isEqualTo(stats.toolCalls());
    assertThat(row.inferenceCalls()).isEqualTo(1);
  }

  @Test
  void a_row_that_cannot_be_written_rolls_the_ending_event_back_too() {
    AtomicInteger refusals = new AtomicInteger();
    engine = new EngineFixture(ANSWERS, listener(), backend -> new RefusingRows(backend, refusals));
    AgentId agent = AgentId.random();

    harness().tell(agent, "hello");

    await().atMost(PATIENCE).until(() -> refusals.get() >= 1);
    List<AgentEvent> story = engine.story(TYPE, agent);
    assertThat(story).isNotEmpty();
    assertThat(story)
        .noneMatch(
            e ->
                e instanceof AgentEvent.InferenceAnswered
                    || e instanceof AgentEvent.InferenceRefused
                    || e instanceof AgentEvent.InferenceFailed
                    || e instanceof AgentEvent.TurnStopped);
    assertThat(engine.backend().turns().of(TYPE, agent)).isEmpty();
  }

  @Test
  void the_effect_span_carries_the_trajectory_hash_the_row_holds() {
    List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
    ObservationRegistry observations = ObservationRegistry.create();
    observations
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStop(Observation.Context context) {
                stopped.add(context);
              }
            });
    engine = new EngineFixture(ANSWERS, listener(), observations);
    AgentId agent = AgentId.random();

    AgentTurn row = tellAndAwaitItsRow(harness(), agent);

    await()
        .atMost(PATIENCE)
        .untilAsserted(
            () ->
                assertThat(stopped)
                    .anySatisfy(
                        c -> {
                          assertThat(c.getName()).isEqualTo("nessy.effect");
                          KeyValue hash = c.getHighCardinalityKeyValue("nessy.trajectory.hash");
                          assertThat(hash).isNotNull();
                          assertThat(hash.getValue()).isEqualTo(row.trajectory().hash());
                        }));
  }

  private static NarrationListener listener() {
    return NarrationListener.none();
  }

  /** A backend whose turn rows cannot be written, counting each refusal. */
  private record RefusingRows(QueuedBackend backend, AtomicInteger refusals)
      implements QueuedBackend {

    @Override
    public AgentEvents events() {
      return backend.events();
    }

    @Override
    public Payloads payloads() {
      return backend.payloads();
    }

    @Override
    public Locks locks() {
      return backend.locks();
    }

    @Override
    public Agents agents() {
      return backend.agents();
    }

    @Override
    public Effects effects() {
      return backend.effects();
    }

    @Override
    public Chapters chapters() {
      return backend.chapters();
    }

    @Override
    public Leases leases() {
      return backend.leases();
    }

    @Override
    public AgentTurns turns() {
      AgentTurns real = backend.turns();
      return new AgentTurns() {
        @Override
        public void record(AgentType type, AgentId agent, AgentTurn turn) {
          refusals.incrementAndGet();
          throw new IllegalStateException("refused");
        }

        @Override
        public List<AgentTurn> of(AgentType type, AgentId agent) {
          return real.of(type, agent);
        }
      };
    }

    @Override
    public <I> Backlogs<I> backlogs(TypeRef<I> inputType) {
      return backend.backlogs(inputType);
    }

    @Override
    public int queued(AgentType type, AgentId agent) {
      return backend.queued(type, agent);
    }
  }
}
