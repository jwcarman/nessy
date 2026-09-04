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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Effect;
import org.jwcarman.nessy.engine.agent.Input;
import org.jwcarman.nessy.engine.agent.Phase;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The loop that used to be a mailbox.
 *
 * <p>EffectWorker are recorded rather than really performed here, because what this class owns is
 * the ORDER: transition first, effects claimed only after it commits, and each one performed in the
 * order the decision put them in. Whether a model call works is EffectWorker' problem.
 */
@DisplayName("The drive loop")
class AgentRuntimeTest {

  private static final AgentType TYPE = AgentType.of("watchman");
  private static final AgentId AGENT = AgentId.of("house-1");

  private EmbeddedDatabase database;
  private AgentStore store;
  private EffectStore effects;
  private Transition transition;
  private List<Effect> performed;
  private AgentRuntime runtime;

  @BeforeEach
  void fresh() {
    database = TestDatabase.fresh();
    store = new AgentStore(database);
    effects = new EffectStore(database);
    transition =
        new Transition(
            TYPE,
            store,
            effects,
            new Reminders(database),
            new TransactionTemplate(new DataSourceTransactionManager(database)));
    performed = new ArrayList<>();
    Executor sameThread = Runnable::run;
    runtime =
        new AgentRuntime(
            TYPE,
            transition,
            effects,
            (agentId, state, turnId, effect, effectId) -> performed.add(effect),
            sameThread,
            Duration.ofMinutes(1),
            Traces.noop());
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  @Test
  @DisplayName("driving a nudge performs the obligation the fold committed")
  void effects_run_after_the_transition() {
    runtime.drive(AGENT, new Input.BacklogUpdated(), null, null);

    assertThat(performed).isNotEmpty();
    assertThat(performed).allMatch(Effect.TakeWork.class::isInstance);
  }

  @Test
  @DisplayName("drive hands the drain to the executor rather than running it inline")
  void drive_returns_before_a_blocked_performer_finishes() {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(1);
    List<Effect> performedAsync = new CopyOnWriteArrayList<>();
    AgentRuntime asyncRuntime =
        new AgentRuntime(
            TYPE,
            transition,
            effects,
            (agentId, state, turnId, effect, effectId) -> {
              awaitQuietly(release, Duration.ofSeconds(5));
              performedAsync.add(effect);
              done.countDown();
            },
            Executors.newVirtualThreadPerTaskExecutor(),
            Duration.ofMinutes(1),
            Traces.noop());

    // If drive() ran work() on the calling thread instead of handing it to the executor, this
    // call would block on the still-held latch and this assertion would time out and fail --
    // it cannot pass because the collection below happens to be empty; it can only pass because
    // drive() actually returned control to this thread while the performer was still blocked.
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> asyncRuntime.drive(AGENT, new Input.BacklogUpdated(), null, null));

    // Proof the drain is genuinely elsewhere: the obligation is claimed and its performer has
    // been entered on another thread, but that thread is still parked on the latch, so nothing
    // has been recorded yet.
    assertThat(performedAsync).isEmpty();

    release.countDown();

    assertThat(awaitQuietly(done, Duration.ofSeconds(5))).isTrue();
    assertThat(performedAsync).isNotEmpty();
    assertThat(performedAsync).allMatch(Effect.TakeWork.class::isInstance);
  }

  @Test
  @DisplayName("the state is committed before any obligation runs")
  void the_transition_commits_first() {
    List<Phase> phaseWhenPerformed = new ArrayList<>();
    AgentRuntime observing =
        new AgentRuntime(
            TYPE,
            transition,
            effects,
            (agentId, state, turnId, effect, effectId) -> phaseWhenPerformed.add(readPhase()),
            Runnable::run,
            Duration.ofMinutes(1),
            Traces.noop());

    observing.drive(AGENT, new Input.BacklogUpdated(), null, null);

    assertThat(phaseWhenPerformed).isNotEmpty();
    assertThat(phaseWhenPerformed).allMatch(Phase.AwaitingWork.class::isInstance);
  }

  @Test
  @DisplayName("obligations are performed in the order the decision put them in")
  void effects_run_in_decision_order() {
    runtime.drive(AGENT, new Input.BacklogUpdated(), null, null);
    performed.clear();

    runtime.drive(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), null, null);

    // Narration is delivered BEFORE the drain is handed off, so it leads the recording. That is
    // the decoupling's visible consequence and worth pinning rather than filtering away silently.
    assertThat(performed).isNotEmpty();
    assertThat(performed.get(0)).isInstanceOf(Effect.Narrate.TurnStarted.class);

    // The ordering that carries meaning is among the DURABLE effects: they are the ones that
    // become rows with an ordinal, and endTurn's Remember-before-Release depends on it.
    List<Effect> durable =
        performed.stream().filter(effect -> Disposition.of(effect) == Disposition.DURABLE).toList();
    assertThat(durable).isNotEmpty();
    assertThat(durable.get(0)).isInstanceOf(Effect.Remember.Input.class);
    assertThat(durable.get(durable.size() - 1)).isInstanceOf(Effect.CallModel.class);
  }

  @Test
  @DisplayName("narration is delivered from the transition and never claimed as work")
  void narration_is_performed_without_being_a_row() {
    runtime.drive(AGENT, new Input.BacklogUpdated(), null, null);
    performed.clear();

    runtime.drive(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), null, null);

    assertThat(performed).isNotEmpty();
    assertThat(performed).anyMatch(Effect.Narrate.TurnStarted.class::isInstance);
    assertThat(effects.claim(TYPE, AGENT, Instant.now().plus(Duration.ofMinutes(1))))
        .noneMatch(
            effect -> EffectStore.PAYLOADS.decode(effect.payload()) instanceof Effect.Narrate);
  }

  @Test
  @DisplayName("a decision with nothing to do performs nothing")
  void an_empty_decision_performs_nothing() {
    runtime.drive(AGENT, new Input.BacklogUpdated(), null, null);
    performed.clear();

    runtime.drive(AGENT, new Input.BacklogUpdated(), null, null);

    assertThat(performed).isEmpty();
  }

  @Test
  @DisplayName("an obligation abandoned by a dead node is reclaimable")
  void abandoned_work_stays_claimable() {
    AgentRuntime dying =
        new AgentRuntime(
            TYPE,
            transition,
            effects,
            (agentId, state, turnId, effect, effectId) -> {
              throw new IllegalStateException("this node just died");
            },
            Runnable::run,
            Duration.ofMillis(-1),
            Traces.noop());

    dying.drive(AGENT, new Input.BacklogUpdated(), null, null);

    assertThat(effects.claimExpired(TYPE, Instant.now(), 10)).isNotEmpty();
  }

  @Test
  @DisplayName("inspect reads the stored state without changing it")
  void inspect_is_a_read() {
    runtime.drive(AGENT, new Input.BacklogUpdated(), null, null);
    performed.clear();

    AgentState seen = runtime.inspect(AGENT);

    assertThat(seen.phase()).isInstanceOf(Phase.AwaitingWork.class);
    assertThat(performed).isEmpty();
  }

  @Test
  @DisplayName("draining an agent nobody has ever heard of creates no row and performs nothing")
  void work_on_an_unknown_agent_creates_no_row() {
    AgentId stranger = AgentId.of("house-stranger");

    runtime.work(stranger);

    assertThat(performed).isEmpty();
    assertThat(transition.peek(stranger))
        .as("peek still finds nobody -- work() did not conjure a row to drain")
        .isEmpty();
  }

  /**
   * C2's whole defect, driven honestly: an obligation decided in one turn is performed against THAT
   * turn even though the agent it belongs to has since moved to a different one. The mismatch is
   * arranged, not assumed -- the two turn ids below are asserted distinct, and the assertion on
   * what the performer actually received is what would fail if {@code work} fell back to reading
   * the turn off current state instead of the claimed effect.
   */
  @Test
  @DisplayName(
      "an effect decided in an earlier turn is performed against that turn, not whichever turn the agent is in now")
  void a_stale_effect_is_performed_against_its_own_turn_not_the_current_one() {
    runtime.drive(AGENT, new Input.BacklogUpdated(), null, null);
    runtime.drive(
        AGENT, new Input.WorkTaken(TurnId.of("turn-current"), "claim-current"), null, null);
    performed.clear();

    // An obligation from an EARLIER turn than the one this agent is in now -- exactly what the
    // recovery path leaves behind when an effect outlives its turn.
    TurnId staleTurn = TurnId.of("turn-stale");
    effects.insert(
        TYPE, AGENT, staleTurn, 0, EffectStore.PAYLOADS.encode(new Effect.Release()), null);

    List<TurnId> turnIdsSeen = new ArrayList<>();
    AgentRuntime capturing =
        new AgentRuntime(
            TYPE,
            transition,
            effects,
            (agentId, state, turnId, effect, effectId) -> turnIdsSeen.add(turnId),
            Runnable::run,
            Duration.ofMinutes(1),
            Traces.noop());

    capturing.work(AGENT);

    TurnId currentTurn = capturing.inspect(AGENT).turnId();
    assertThat(currentTurn)
        .as("the mismatch this test relies on: the agent has genuinely moved on")
        .isNotEqualTo(staleTurn);
    assertThat(turnIdsSeen).isNotEmpty();
    assertThat(turnIdsSeen)
        .as("the performer was addressed at the STALE turn, never the agent's current one")
        .containsOnly(staleTurn);
  }

  @Test
  @DisplayName("an answer-shaped input that changes nothing is reported, not silently absorbed")
  void a_dropped_answer_is_logged_and_tagged() {
    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(AgentRuntime.class);
    ListAppender<ILoggingEvent> appended = new ListAppender<>();
    appended.start();
    logger.addAppender(appended);
    try {
      // A fresh, idle agent is not awaiting any call, so AgentLogic.decide drops this and returns
      // Decision.nothing -- the exact "an event tied to an unresolvable address" shape C5 names.
      runtime.drive(
          AGENT,
          new Input.ApprovalGiven(
              CallId.of("call-nobody-asked"), "some-tool", ApprovalResult.approved()),
          null,
          null);

      assertThat(appended.list).isNotEmpty();
      assertThat(appended.list)
          .anyMatch(
              event ->
                  event.getLevel() == Level.WARN
                      && event.getFormattedMessage().contains("dropped"));
    } finally {
      logger.detachAppender(appended);
    }
  }

  @Test
  @DisplayName("an answer-shaped input that DID change something is not reported as dropped")
  void an_effective_answer_is_not_reported_as_dropped() {
    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(AgentRuntime.class);
    ListAppender<ILoggingEvent> appended = new ListAppender<>();
    appended.start();
    logger.addAppender(appended);
    try {
      // WorkTaken while genuinely AwaitingWork changes the agent -- not a drop.
      runtime.drive(AGENT, new Input.BacklogUpdated(), null, null);
      runtime.drive(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), null, null);

      assertThat(appended.list)
          .noneMatch(
              event ->
                  event.getLevel() == Level.WARN
                      && event.getFormattedMessage().contains("dropped"));
    } finally {
      logger.detachAppender(appended);
    }
  }

  private Phase readPhase() {
    return new TransactionTemplate(new DataSourceTransactionManager(database))
        .execute(status -> store.lockAndLoad(TYPE, AGENT))
        .phase();
  }

  /** A bounded wait with no checked exception to smuggle out of a lambda. */
  private static boolean awaitQuietly(CountDownLatch latch, Duration timeout) {
    try {
      return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
