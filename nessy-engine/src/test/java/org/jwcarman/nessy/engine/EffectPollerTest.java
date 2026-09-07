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
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.agent.Effect;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only place a durable effect is ever attempted.
 *
 * <p>{@link AgentRuntime} here is wired against a FAKE {@code Performer} -- what this class owns is
 * grouping, sequencing and commit-before-execute, none of which needs a real {@code EffectWorker}
 * to observe.
 */
@DisplayName("The effect poller")
class EffectPollerTest {

  private static final AgentType TYPE = AgentType.of("watchman");
  private static final Duration TIMEOUT = Duration.ofMinutes(1);

  private EmbeddedDatabase database;
  private EffectStore effects;
  private AgentStore store;
  private Transition transition;

  @BeforeEach
  void fresh() {
    database = TestDatabase.fresh();
    effects = new EffectStore(database);
    store = new AgentStore(database);
    transition =
        new Transition(
            TYPE,
            store,
            effects,
            new TransactionTemplate(new DataSourceTransactionManager(database)));
    // Every agent this test uses must have a real nessy_agent row, or AgentRuntime#peek finds
    // nobody and the poller skips the row entirely -- see EffectPoller#runAgent.
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  @Test
  @DisplayName("one pass attempts everything due and reports how many rows it found")
  void pollOnce_attempts_the_due_batch_and_reports_its_size() {
    AgentId agent = agent("house-1");
    insert(agent, 0, new Effect.Release());
    insert(agent, 1, new Effect.Forget());
    CopyOnWriteArrayList<Effect> seen = new CopyOnWriteArrayList<>();
    EffectPoller poller =
        poller((agentId, state, turnId, effect, effectId, attempts) -> seen.add(effect));

    int found = poller.pollOnce();

    assertThat(found).isEqualTo(2);
    assertThat(seen).hasSize(2);
  }

  @Test
  @DisplayName("a row is marked RUNNING and committed BEFORE the performer ever sees it")
  void the_row_is_committed_running_before_the_performer_runs() {
    AgentId agent = agent("house-1");
    insert(agent, 0, new Effect.Release());
    List<String> statusDuringPerform = new CopyOnWriteArrayList<>();
    EffectPoller poller =
        poller(
            (agentId, state, turnId, effect, effectId, attempts) ->
                statusDuringPerform.add(statusOf(effectIdOf(agentId))));

    poller.pollOnce();

    // Read from a SEPARATE JdbcClient call, outside any transaction the poller holds -- if
    // EffectStore#attempt had not already committed the RUNNING status before handing the row to
    // the performer, this read (on this same thread, after attempt() returned but conceptually
    // representing "another node querying the row mid-flight") would still see PENDING or would
    // race the poller's own uncommitted write.
    assertThat(statusDuringPerform).containsExactly("RUNNING");
  }

  @Test
  @DisplayName("one agent's rows run strictly in ORDINAL order, not actionable_at/insertion order")
  void one_agents_rows_run_in_ordinal_order_not_insertion_order() {
    AgentId agent = agent("house-1");
    // Inserted with ordinal DESCENDING against insertion/actionable_at order, so a sort that
    // (wrongly) trusted attempt()'s own actionable_at ordering would see them backwards.
    insert(agent, 2, new Effect.Remember.Exchange());
    insert(agent, 1, new Effect.Remember.Answer());
    insert(agent, 0, new Effect.Remember.Input());
    List<Effect> seenInOrder = new CopyOnWriteArrayList<>();
    EffectPoller poller =
        poller((agentId, state, turnId, effect, effectId, attempts) -> seenInOrder.add(effect));

    poller.pollOnce();

    assertThat(seenInOrder)
        .hasSize(3)
        .satisfiesExactly(
            e -> assertThat(e).isInstanceOf(Effect.Remember.Input.class),
            e -> assertThat(e).isInstanceOf(Effect.Remember.Answer.class),
            e -> assertThat(e).isInstanceOf(Effect.Remember.Exchange.class));
  }

  /**
   * The honest version of the sequencing property: it does not merely assert the OUTPUT was in
   * order (which a lucky race could also produce), it makes the SECOND effect's performer actively
   * check, from inside itself, that the first has already finished -- and forces the first to be
   * slow enough that a per-row (rather than per-agent) executor would almost certainly start the
   * second while the first is still running. Removing {@code EffectPoller}'s grouping-by-agent (or
   * replacing the per-agent sequential loop with one more hand-off per row) makes this fail: the
   * ONLY thing standing between "first" and "second" running concurrently is that they are handed
   * to the SAME virtual thread as one sequential loop.
   */
  @Test
  @DisplayName("two effects of the SAME agent never run concurrently, even under a real executor")
  void same_agent_effects_never_overlap_under_a_real_executor() {
    AgentId agent = agent("house-1");
    insert(agent, 0, new Effect.Release());
    insert(agent, 1, new Effect.Forget());
    AtomicBoolean firstFinished = new AtomicBoolean(false);
    AtomicBoolean overlapDetected = new AtomicBoolean(false);
    EffectPoller poller =
        poller(
            Executors.newVirtualThreadPerTaskExecutor(),
            (agentId, state, turnId, effect, effectId, attempts) -> {
              if (effect instanceof Effect.Release) {
                sleepQuietly(Duration.ofMillis(250));
                firstFinished.set(true);
              } else {
                if (!firstFinished.get()) {
                  overlapDetected.set(true);
                }
              }
            });

    poller.pollOnce();

    assertThat(overlapDetected)
        .as("the second effect started while the first (deliberately slow) one was still running")
        .isFalse();
  }

  @Test
  @DisplayName("different agents run fully in parallel, not serialized behind one another")
  void different_agents_run_in_parallel() throws Exception {
    AgentId first = agent("house-1");
    AgentId second = agent("house-2");
    insert(first, 0, new Effect.Release());
    insert(second, 0, new Effect.Release());
    // Both branches must reach the barrier for it to release -- if the poller serialized
    // cross-agent work (the bug this test guards against, e.g. an accidental single-thread
    // executor or an accidental agent-level lock), the second task would never even START until
    // the first one, still blocked awaiting the barrier, finished -- and this would time out.
    CyclicBarrierLike barrier = new CyclicBarrierLike(2);
    EffectPoller poller =
        poller(
            Executors.newVirtualThreadPerTaskExecutor(),
            (agentId, state, turnId, effect, effectId, attempts) -> barrier.arriveAndAwait());

    boolean bothArrived = barrier.completeWithin(poller::pollOnce, Duration.ofSeconds(5));

    assertThat(bothArrived)
        .as("both agents' work reached the barrier concurrently -- neither waited on the other")
        .isTrue();
  }

  @Test
  @DisplayName("an agent forgotten between due and attempted is skipped, not thrown")
  void a_forgotten_agent_is_skipped_without_throwing() {
    // A row naming an agent that never got a nessy_agent row at all -- the sharpest version of
    // "forgotten before this pass got to it": peek() finds nobody.
    AgentId ghost = AgentId.of("house-ghost");
    effects.insert(
        TYPE, ghost, null, null, 0, EffectStore.PAYLOADS.encode(new Effect.Forget()), null);
    CopyOnWriteArrayList<Effect> seen = new CopyOnWriteArrayList<>();
    EffectPoller poller =
        poller((agentId, state, turnId, effect, effectId, attempts) -> seen.add(effect));

    int found = poller.pollOnce();

    assertThat(found)
        .as("the row was still attempted -- attempt() does not know about peek()")
        .isEqualTo(1);
    assertThat(seen)
        .as("but never performed, since no state exists to perform it against")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "F1: an abandoned effect holds back and defers its siblings, same as a retried one -- being"
          + " held back is not a failure, whichever way the line broke")
  void an_abandoned_effect_defers_its_siblings_too() {
    AgentId agent = agent("house-1");
    insert(agent, 0, new Effect.Remember.Input());
    insert(agent, 1, new Effect.Release());
    EffectPoller poller =
        poller(
            (agentId, state, turnId, effect, effectId, attempts) -> {
              // Simulates giveUp()'s synchronous abandon -- ordinal 0's obligation is exhausted
              // on this very pass, synchronously, exactly as EffectWorker#giveUp does before any
              // external work runs. Ordinal 1 (Release) is never performed in this pass at all.
              if (effect instanceof Effect.Remember.Input) {
                effects.abandon(effectId, "gave up after 3 failures");
              }
            });

    int found = poller.pollOnce();

    assertThat(found).isEqualTo(2);
    EffectId siblingId = effectIdFor(agent, 1);
    assertThat(statusOf(siblingId))
        .as(
            "F1: the abandon branch defers siblings too -- left RUNNING, the sibling's next"
                + " pickup would be charged a phantom failure by TAKE's conditional increment")
        .isEqualTo("PENDING");
  }

  private EffectId effectIdFor(AgentId agentId, int ordinal) {
    String id =
        JdbcClient.create(database)
            .sql("SELECT effect_id FROM nessy_effect WHERE agent_id = ? AND ordinal = ?")
            .param(agentId.value())
            .param(ordinal)
            .query(String.class)
            .single();
    return EffectId.of(id);
  }

  private AgentId agent(String value) {
    AgentId agentId = AgentId.of(value);
    // read(), not apply(): conjures the idle nessy_agent row (see Transition#read) WITHOUT
    // folding a decision -- apply(Recovered) would itself commit a TakeWork effect, polluting
    // every count and ordering assertion below with a row this test never asked for.
    transition.read(agentId);
    return agentId;
  }

  private void insert(AgentId agentId, int ordinal, Effect effect) {
    effects.insert(
        TYPE,
        agentId,
        TurnId.of("turn-1"),
        null,
        ordinal,
        EffectStore.PAYLOADS.encode(effect),
        null);
  }

  private EffectPoller poller(AgentRuntime.Performer performer) {
    return poller(Runnable::run, performer);
  }

  private EffectPoller poller(Executor agentExecutor, AgentRuntime.Performer performer) {
    return new EffectPoller(
        TYPE,
        effects,
        runtimeWith(performer),
        new PollSchedule(Duration.ofMillis(10), Duration.ofSeconds(1), 2.0, 0.0, new Random(1)),
        agentExecutor,
        100,
        TIMEOUT);
  }

  private AgentRuntime runtimeWith(AgentRuntime.Performer performer) {
    return new AgentRuntime(TYPE, transition, performer, Runnable::run, Traces.noop());
  }

  private String statusOf(EffectId effectId) {
    if (effectId == null) {
      return null;
    }
    return JdbcClient.create(database)
        .sql("SELECT status FROM nessy_effect WHERE effect_id = ?")
        .param(effectId.value())
        .query(String.class)
        .single();
  }

  // The fake performer above does not receive an EffectId, so this test instead re-reads the
  // single row this test inserted -- there is exactly one, by construction.
  private EffectId effectIdOf(AgentId agentId) {
    String id =
        JdbcClient.create(database)
            .sql("SELECT effect_id FROM nessy_effect WHERE agent_id = ?")
            .param(agentId.value())
            .query(String.class)
            .single();
    return EffectId.of(id);
  }

  private static void sleepQuietly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * A minimal rendezvous point, built by hand rather than reaching for {@code
   * java.util.concurrent.CyclicBarrier} directly in the test body -- it exists so the test can ask
   * one clean question ("did every party arrive within the deadline") instead of juggling {@code
   * BrokenBarrierException} inline.
   */
  private static final class CyclicBarrierLike {
    private final java.util.concurrent.CyclicBarrier barrier;

    CyclicBarrierLike(int parties) {
      this.barrier = new java.util.concurrent.CyclicBarrier(parties);
    }

    void arriveAndAwait() {
      try {
        barrier.await(5, TimeUnit.SECONDS);
      } catch (Exception e) {
        // Left un-arrived; completeWithin's join will simply not see every party arrive in time.
        Thread.currentThread().interrupt();
      }
    }

    boolean completeWithin(Runnable action, Duration timeout) throws InterruptedException {
      Thread runner = new Thread(action);
      runner.start();
      runner.join(timeout.toMillis());
      boolean finished = !runner.isAlive();
      if (!finished) {
        runner.interrupt();
      }
      return finished;
    }
  }
}
