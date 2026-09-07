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

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
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
import org.jwcarman.nessy.api.CallId;
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

  /** Two turns of one agent, named so that their order is the order they were minted in. */
  private static final TurnId EARLIER_TURN = TurnId.of("turn-1");

  private static final TurnId LATER_TURN = TurnId.of("turn-2");

  private EmbeddedDatabase database;
  private EffectStore effects;
  private AgentStore store;
  private Transition transition;

  @BeforeEach
  void fresh() {
    database = TestDatabase.fresh();
    effects = new EffectStore(database);
    store = new AgentStore(database, Clock.systemUTC());
    transition =
        new Transition(
            TYPE,
            store,
            effects,
            new TransactionTemplate(new DataSourceTransactionManager(database)));
    // Every agent this test uses must have a real nessy_agent row, or AgentRuntime#peek finds
    // nobody and the poller abandons the row rather than performing it -- see
    // EffectPoller#runAgent.
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
  @DisplayName("an agent forgotten between due and attempted is abandoned, not thrown")
  void a_forgotten_agent_is_abandoned_without_throwing() {
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
    assertThat(statusesFor(ghost))
        .as("abandoned, not left for a later pass to find again")
        .containsExactly("FAILED");
    assertThat(reasonsFor(ghost))
        .as("the reason names the ghost, not a generic exhaustion")
        .allSatisfy(
            reason -> assertThat(reason).contains(ghost.value()).contains("no longer exists"));
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

  @Test
  @DisplayName(
      "R-AH: an effect whose agent no longer exists is abandoned with a reason naming it -- the"
          + " whole group at once, and its attempts stop climbing")
  void an_effect_for_a_vanished_agent_is_abandoned() {
    AgentId vanished = agent("house-vanished");
    insert(vanished, 0, new Effect.Remember.Answer());
    insert(vanished, 1, new Effect.Release());
    insert(vanished, 2, new Effect.Narrate.ToolCallCompleted(CallId.of("call-1")));
    // The race EffectWorker#forget can lose: it deletes an agent's effect rows BEFORE the agent
    // itself, so a transition committing new effects in between leaves rows behind whose agent is
    // already on its way out. peek() then finds nobody -- and it means GONE, never NOT YET:
    // Transition#record is the only thing that ever inserts an effect, and it runs inside the same
    // transaction as the lockAndLoad that brings the agent row into existence.
    store.delete(TYPE, vanished);

    CopyOnWriteArrayList<Effect> seen = new CopyOnWriteArrayList<>();
    // A watchdog of a millisecond, so every later pass in this test finds these rows due again --
    // which is exactly the condition under which TAKE's conditional increment charges a failure.
    EffectPoller poller =
        poller(
            Runnable::run,
            (agentId, state, turnId, effect, effectId, attempts) -> seen.add(effect),
            Duration.ofMillis(1));

    List<List<Integer>> attemptsAfterEachPass = new ArrayList<>();
    for (int pass = 0; pass < 3; pass++) {
      poller.pollOnce();
      attemptsAfterEachPass.add(attemptsFor(vanished));
      sleepQuietly(Duration.ofMillis(20)); // the millisecond watchdog lapses well within this
    }

    assertThat(seen)
        .as("nothing was performed -- there is no state to perform it against")
        .isEmpty();
    assertThat(attemptsAfterEachPass)
        .as(
            "R-AH: abandoning charges the one failure it is (ABANDON increments), and then the"
                + " row is terminal with a NULL actionable_at, so no later pass can find it and"
                + " charge it again. Left RUNNING instead, all three rows come due on every"
                + " watchdog lapse forever and TAKE charges each of them a fresh phantom failure"
                + " every time, with giveUp unreachable above perform() to ever close them.")
        .containsExactly(List.of(1, 1, 1), List.of(1, 1, 1), List.of(1, 1, 1));
    assertThat(statusesFor(vanished))
        .as(
            "every row of the group is terminal, not just the head -- all are equally undeliverable")
        .containsExactly("FAILED", "FAILED", "FAILED");
    assertThat(reasonsFor(vanished))
        .as(
            "and the reason names the vanished agent, so an operator can tell 'forgotten out from"
                + " under its work' from 'this obligation failed five times'")
        .allSatisfy(
            reason ->
                assertThat(reason)
                    .contains(vanished.value())
                    .contains("no longer exists")
                    .doesNotContain("gave up after"));
  }

  @Test
  @DisplayName(
      "F3: a group spanning two turns is stopped whole -- the later turn's rows this pass claimed"
          + " are released too, not left RUNNING for TAKE to charge a phantom failure")
  void a_group_spanning_two_turns_releases_every_un_run_row() {
    AgentId agent = agent("house-1");
    // One agent, two turns, all due at once -- what the throw path makes reachable: TakeWork runs
    // past an outstanding turn-1 row and starts turn 2, and a watchdog later both turns' rows land
    // in the SAME group.
    insert(agent, EARLIER_TURN, 0, new Effect.Remember.Input());
    insert(agent, EARLIER_TURN, 1, new Effect.Release());
    insert(agent, LATER_TURN, 0, new Effect.Remember.Answer());
    insert(agent, LATER_TURN, 1, new Effect.Release());
    CopyOnWriteArrayList<Effect> seen = new CopyOnWriteArrayList<>();
    EffectPoller poller =
        poller(
            (agentId, state, turnId, effect, effectId, attempts) -> {
              seen.add(effect);
              // giveUp()'s synchronous abandon on the FIRST row of the FIRST turn: the group stops
              // here, and every row after it -- in either turn -- was claimed and not run.
              if (effect instanceof Effect.Remember.Input) {
                effects.abandon(effectId, "gave up after 3 failures");
              }
            });

    int found = poller.pollOnce();

    assertThat(found).isEqualTo(4);
    assertThat(seen).as("the group stopped on its first row").hasSize(1);
    assertThat(statusesFor(agent))
        .as(
            "every un-run row is back to PENDING, whichever turn it belongs to. A turn-scoped"
                + " deferral leaves the later turn's rows RUNNING and un-run, and TAKE's"
                + " conditional increment charges each of them a failure it never made the next"
                + " time its watchdog lapses.")
        .containsExactly("FAILED", "PENDING", "PENDING", "PENDING");
  }

  @Test
  @DisplayName(
      "F3: a group spanning two turns runs the EARLIER turn's rows first -- ordinal alone would"
          + " run the later turn's ordinal 0 in front of the earlier turn's ordinal 3")
  void a_group_spanning_two_turns_runs_the_earlier_turn_first() {
    AgentId agent = agent("house-1");
    // Inserted so that actionable_at order (which is what attempt() returns) puts the LATER turn
    // first, and its ordinal is lower too: only a sort that knows about turns can get this right.
    // Turn ids really are ordered -- Identifiers mints UUIDv7 -- and these two stand in for that.
    insert(agent, LATER_TURN, 0, new Effect.Remember.Answer());
    insert(agent, EARLIER_TURN, 3, new Effect.Release());
    List<TurnId> turnsInOrder = new CopyOnWriteArrayList<>();
    EffectPoller poller =
        poller((agentId, state, turnId, effect, effectId, attempts) -> turnsInOrder.add(turnId));

    poller.pollOnce();

    assertThat(turnsInOrder).containsExactly(EARLIER_TURN, LATER_TURN);
  }

  @Test
  @DisplayName(
      "F3: a turn-less row stops its group like any other -- turn_id = NULL matches nothing in"
          + " SQL, so a group stopped by an agent's first-ever TakeWork used to release nobody")
  void a_turnless_row_still_releases_its_siblings() {
    AgentId agent = agent("house-1");
    // What an agent's first activation commits: TakeWork before any turn exists, so turn_id is
    // NULL. AgentLogic emits one effect there, but a decision with a narration between two durable
    // effects leaves gaps in the ordinals, so a turn-less group of two is not a shape to rule out.
    insert(agent, null, 0, new Effect.TakeWork());
    insert(agent, null, 1, new Effect.Release());
    EffectPoller poller =
        poller(
            (agentId, state, turnId, effect, effectId, attempts) -> {
              if (effect instanceof Effect.TakeWork) {
                effects.abandon(effectId, "the backlog is unreadable");
              }
            });

    poller.pollOnce();

    assertThat(statusesFor(agent))
        .as("the turn-less sibling is released, not left RUNNING to be charged a phantom failure")
        .containsExactly("FAILED", "PENDING");
  }

  private List<Integer> attemptsFor(AgentId agentId) {
    return columnFor(agentId, "attempts", Integer.class);
  }

  private List<String> statusesFor(AgentId agentId) {
    return columnFor(agentId, "status", String.class);
  }

  private List<String> reasonsFor(AgentId agentId) {
    return columnFor(agentId, "reason", String.class);
  }

  /** One column of every row this agent owes, in (turn, ordinal) order. */
  private <T> List<T> columnFor(AgentId agentId, String column, Class<T> type) {
    return JdbcClient.create(database)
        .sql(
            "SELECT "
                + column
                + " FROM nessy_effect WHERE agent_id = ? ORDER BY COALESCE(turn_id, ''), ordinal")
        .param(agentId.value())
        .query(type)
        .list();
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
    insert(agentId, EARLIER_TURN, ordinal, effect);
  }

  /** As above, for the tests that care WHICH turn a row belongs to. */
  private void insert(AgentId agentId, TurnId turnId, int ordinal, Effect effect) {
    effects.insert(TYPE, agentId, turnId, null, ordinal, EffectStore.PAYLOADS.encode(effect), null);
  }

  private EffectPoller poller(AgentRuntime.Performer performer) {
    return poller(Runnable::run, performer);
  }

  private EffectPoller poller(Executor agentExecutor, AgentRuntime.Performer performer) {
    return poller(agentExecutor, performer, TIMEOUT);
  }

  /** As above, with the watchdog a row is marked RUNNING with chosen by the caller. */
  private EffectPoller poller(
      Executor agentExecutor, AgentRuntime.Performer performer, Duration timeout) {
    return new EffectPoller(
        TYPE,
        effects,
        runtimeWith(performer),
        new PollSchedule(Duration.ofMillis(10), Duration.ofSeconds(1), 2.0, 0.0, new Random(1)),
        agentExecutor,
        100,
        timeout);
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
