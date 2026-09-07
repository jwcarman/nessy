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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
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
 * The loop that was a mailbox.
 *
 * <p>{@link EffectWorker} is recorded rather than really performed here, because what this class
 * owns is ORDER and DECOUPLING: transition commits and returns without performing anything, an
 * obligation attempted off the store is performed against the TURN it was decided in, and a failure
 * thrown out of a performer leaves the row outstanding rather than crashing the caller. Whether a
 * model call works is {@code EffectWorker}'s problem.
 */
@DisplayName("The drive loop")
class AgentRuntimeTest {

  private static final AgentType TYPE = AgentType.of("watchman");
  private static final AgentId AGENT = AgentId.of("house-1");
  private static final Duration TIMEOUT = Duration.ofMinutes(1);

  private EmbeddedDatabase database;
  private AgentStore store;
  private EffectStore effects;
  private Transition transition;
  private List<Effect> performed;
  private AgentRuntime runtime;

  @BeforeEach
  void fresh() {
    database = TestDatabase.fresh();
    store = new AgentStore(database, Clock.systemUTC());
    effects = new EffectStore(database);
    transition =
        new Transition(
            TYPE,
            store,
            effects,
            new TransactionTemplate(new DataSourceTransactionManager(database)));
    performed = new ArrayList<>();
    Executor sameThread = Runnable::run;
    runtime =
        new AgentRuntime(
            TYPE,
            transition,
            (agentId, state, turnId, effect, effectId, attempts) -> performed.add(effect),
            sameThread,
            Traces.noop());
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  @Test
  @DisplayName("drive commits the fold and delivers narration, but performs no durable effect")
  void drive_never_performs_a_durable_effect() {
    runtime.drive(AGENT, new Input.BacklogUpdated(), null, null);

    // The narration this decision produced IS delivered (TurnStarted is NARRATION-disposition on
    // some inputs, but a bare BacklogUpdated on an idle agent produces none) -- what matters here
    // is that nothing DURABLE was performed: the TakeWork this decision committed is still sitting
    // in nessy_effect, unclaimed, because drive() no longer hands off to anything that performs it.
    assertThat(performed).noneMatch(Effect.TakeWork.class::isInstance);
    assertThat(effects.attempt(TYPE, 100, Instant.now(), TIMEOUT))
        .as("the committed TakeWork is still there, waiting for a poller")
        .anyMatch(
            effect -> EffectStore.PAYLOADS.decode(effect.payload()) instanceof Effect.TakeWork);
  }

  @Test
  @DisplayName("the state is already committed by the time perform() runs")
  void the_transition_commits_before_perform_runs() {
    List<Phase> phaseWhenPerformed = new ArrayList<>();
    AgentRuntime observing =
        new AgentRuntime(
            TYPE,
            transition,
            (agentId, state, turnId, effect, effectId, attempts) ->
                phaseWhenPerformed.add(readPhase()),
            Runnable::run,
            Traces.noop());

    observing.drive(AGENT, new Input.BacklogUpdated(), null, null);
    AgentState state = observing.peek(AGENT).orElseThrow();
    for (EffectStore.Attempted attempted : effects.attempt(TYPE, 100, Instant.now(), TIMEOUT)) {
      observing.perform(AGENT, state, attempted);
    }

    assertThat(phaseWhenPerformed).isNotEmpty();
    assertThat(phaseWhenPerformed).allMatch(Phase.AwaitingWork.class::isInstance);
  }

  @Test
  @DisplayName("obligations are performed in the order the decision put them in")
  void effects_run_in_decision_order() {
    runtime.drive(AGENT, new Input.BacklogUpdated(), null, null);
    effects.attempt(TYPE, 100, Instant.now(), TIMEOUT);
    performed.clear();

    runtime.drive(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), null, null);

    // Narration is delivered from drive() itself, so it leads the recording; the durable effects
    // this decision committed are performed separately, in the order attempt() hands them back --
    // which is decision order because there is exactly one agent and effects.attempt sorts by
    // actionable_at, all equal here, breaking no ties that matter within one agent's own batch.
    assertThat(performed).isNotEmpty();
    assertThat(performed.get(0)).isInstanceOf(Effect.Narrate.TurnStarted.class);

    performed.clear();
    AgentState state = runtime.peek(AGENT).orElseThrow();
    List<EffectStore.Attempted> durableRows = effects.attempt(TYPE, 100, Instant.now(), TIMEOUT);
    for (EffectStore.Attempted attempted : durableRows) {
      runtime.perform(AGENT, state, attempted);
    }

    assertThat(performed).isNotEmpty();
    assertThat(performed.get(0)).isInstanceOf(Effect.Remember.Input.class);
    assertThat(performed.get(performed.size() - 1)).isInstanceOf(Effect.CallModel.class);
  }

  @Test
  @DisplayName("narration is delivered from the transition and never claimed as work")
  void narration_is_performed_without_being_a_row() {
    runtime.drive(AGENT, new Input.BacklogUpdated(), null, null);
    performed.clear();

    runtime.drive(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), null, null);

    assertThat(performed).isNotEmpty();
    assertThat(performed).anyMatch(Effect.Narrate.TurnStarted.class::isInstance);
    assertThat(effects.attempt(TYPE, 100, Instant.now(), TIMEOUT))
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
  @DisplayName(
      "a performer that throws leaves the obligation outstanding, and PROPAGATES -- R-AD, Task 7"
          + " fix round 3: EffectPoller, not AgentRuntime, is what has to know a throw happened,"
          + " so it can stop the rest of its group")
  void a_thrown_performer_leaves_the_row_outstanding_and_propagates() {
    AgentRuntime dying =
        new AgentRuntime(
            TYPE,
            transition,
            (agentId, state, turnId, effect, effectId, attempts) -> {
              throw new IllegalStateException("this node just died");
            },
            Runnable::run,
            Traces.noop());
    dying.drive(AGENT, new Input.BacklogUpdated(), null, null);
    AgentState state = dying.peek(AGENT).orElseThrow();
    EffectStore.Attempted attempted = effects.attempt(TYPE, 100, Instant.now(), TIMEOUT).get(0);

    assertThatThrownBy(() -> dying.perform(AGENT, state, attempted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("this node just died");

    // The row is untouched (still RUNNING, watchdog armed by attempt() above) -- neither
    // completed nor abandoned -- so it is reclaimable once that watchdog lapses.
    assertThat(effects.attempt(TYPE, 100, Instant.now().plus(Duration.ofHours(1)), TIMEOUT))
        .anyMatch(row -> row.id().equals(attempted.id()));
  }

  @Test
  @DisplayName("peek reads the stored state without changing it, and finds nobody for a stranger")
  void peek_is_a_read_and_finds_nobody_for_a_stranger() {
    runtime.drive(AGENT, new Input.BacklogUpdated(), null, null);
    performed.clear();

    assertThat(runtime.peek(AGENT)).isPresent();
    assertThat(runtime.peek(AGENT).orElseThrow().phase()).isInstanceOf(Phase.AwaitingWork.class);
    assertThat(runtime.peek(AgentId.of("house-stranger"))).isEmpty();
    assertThat(performed).isEmpty();
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

  /**
   * C2's whole defect, driven honestly: an obligation decided in one turn is performed against THAT
   * turn even though the agent it belongs to has since moved to a different one. The mismatch is
   * arranged, not assumed -- the two turn ids below are asserted distinct, and the assertion on
   * what the performer actually received is what would fail if {@code perform} fell back to reading
   * the turn off current state instead of the attempted effect.
   */
  @Test
  @DisplayName(
      "an effect decided in an earlier turn is performed against that turn, not whichever turn the agent is in now")
  void a_stale_effect_is_performed_against_its_own_turn_not_the_current_one() {
    runtime.drive(AGENT, new Input.BacklogUpdated(), null, null);
    runtime.drive(
        AGENT, new Input.WorkTaken(TurnId.of("turn-current"), "claim-current"), null, null);
    // Drains turn-current's own obligations first, through the ORIGINAL runtime -- otherwise they
    // would still be sitting due when capturing.perform runs below, and this test would see every
    // turn id they carry mixed in with the stale one it actually means to isolate.
    AgentState currentState = runtime.peek(AGENT).orElseThrow();
    for (EffectStore.Attempted attempted : effects.attempt(TYPE, 100, Instant.now(), TIMEOUT)) {
      runtime.perform(AGENT, currentState, attempted);
    }
    performed.clear();

    // An obligation from an EARLIER turn than the one this agent is in now -- exactly what the
    // recovery path leaves behind when an effect outlives its turn.
    TurnId staleTurn = TurnId.of("turn-stale");
    effects.insert(
        TYPE, AGENT, staleTurn, null, 0, EffectStore.PAYLOADS.encode(new Effect.Release()), null);

    List<TurnId> turnIdsSeen = new ArrayList<>();
    AgentRuntime capturing =
        new AgentRuntime(
            TYPE,
            transition,
            (agentId, state, turnId, effect, effectId, attempts) -> turnIdsSeen.add(turnId),
            Runnable::run,
            Traces.noop());

    AgentState state = capturing.peek(AGENT).orElseThrow();
    List<EffectStore.Attempted> due = effects.attempt(TYPE, 100, Instant.now(), TIMEOUT);
    for (EffectStore.Attempted attempted : due) {
      capturing.perform(AGENT, state, attempted);
    }

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
}
