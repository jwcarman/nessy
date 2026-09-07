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

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.engine.agent.Effect;
import org.jwcarman.nessy.engine.agent.Input;
import org.jwcarman.nessy.engine.agent.Phase;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lock, fold, persist, commit.
 *
 * <p>What is worth testing is not that a decision comes back -- AgentLogic's own tests cover that
 * -- but that the decision was made against the STORED state, that what it decided is what was
 * stored, and that its effects landed in the SAME transaction. A transition that folded correctly
 * and committed its effects separately would pass every logic test in the repository and lose work
 * to any crash in between.
 */
@DisplayName("One atomic transition")
class TransitionTest {

  private static final AgentType TYPE = AgentType.of("watchman");
  private static final AgentId AGENT = AgentId.of("house-1");
  private static final Duration TIMEOUT = Duration.ofMinutes(1);

  private EmbeddedDatabase database;
  private AgentStore store;
  private EffectStore effects;
  private Transition transition;

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
            new TransactionTemplate(new DataSourceTransactionManager(database)));
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  @Test
  @DisplayName("an idle agent told the backlog moved leaves a take behind as a row")
  void a_nudge_becomes_a_durable_effect() {
    transition.apply(AGENT, new Input.BacklogUpdated(), null, null);

    List<EffectStore.Attempted> pending = effects.attempt(TYPE, 100, Instant.now(), TIMEOUT);

    assertThat(pending).isNotEmpty();
    assertThat(pending)
        .allMatch(
            effect -> EffectStore.PAYLOADS.decode(effect.payload()) instanceof Effect.TakeWork);
  }

  @Test
  @DisplayName("the next transition sees what the last one persisted")
  void state_carries_across_transitions() {
    transition.apply(AGENT, new Input.BacklogUpdated(), null, null);

    Transition.Applied started =
        transition.apply(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), null, null);

    assertThat(started.next().turnId()).isEqualTo(TurnId.of("turn-1"));
    assertThat(started.next().phase()).isInstanceOf(Phase.CallingModel.class);
  }

  @Test
  @DisplayName("effects keep the order the decision put them in")
  void effects_keep_decision_order() {
    transition.apply(AGENT, new Input.BacklogUpdated(), null, null);
    effects.attempt(TYPE, 100, Instant.now(), TIMEOUT);
    transition.apply(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), null, null);

    List<EffectStore.Attempted> pending = effects.attempt(TYPE, 100, Instant.now(), TIMEOUT);

    assertThat(pending).isNotEmpty();
    assertThat(pending)
        .isSortedAccordingTo(java.util.Comparator.comparingInt(EffectStore.Attempted::ordinal));
    assertThat(EffectStore.PAYLOADS.decode(pending.get(0).payload()))
        .isInstanceOf(Effect.Remember.Input.class);
  }

  @Test
  @DisplayName("narration comes back to be delivered, and is never a row")
  void narration_is_returned_not_stored() {
    transition.apply(AGENT, new Input.BacklogUpdated(), null, null);
    effects.attempt(TYPE, 100, Instant.now(), TIMEOUT);

    Transition.Applied started =
        transition.apply(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), null, null);

    assertThat(started.narrations()).isNotEmpty();
    assertThat(started.narrations()).allMatch(Effect.Narrate.class::isInstance);
    List<EffectStore.Attempted> pending = effects.attempt(TYPE, 100, Instant.now(), TIMEOUT);
    assertThat(pending).isNotEmpty();
    assertThat(pending)
        .noneMatch(
            effect -> EffectStore.PAYLOADS.decode(effect.payload()) instanceof Effect.Narrate);
  }

  @Test
  @DisplayName("a decision that changes nothing writes nothing")
  void an_unchanged_decision_is_not_persisted() {
    transition.apply(AGENT, new Input.BacklogUpdated(), null, null);
    effects.attempt(TYPE, 100, Instant.now(), TIMEOUT);

    Transition.Applied repeat = transition.apply(AGENT, new Input.BacklogUpdated(), null, null);

    assertThat(repeat.narrations()).isEmpty();
    assertThat(effects.attempt(TYPE, 100, Instant.now(), TIMEOUT)).isEmpty();
    assertThat(repeat.next().phase()).isInstanceOf(Phase.AwaitingWork.class);
  }

  @Test
  @DisplayName("the effect that produced this input is discharged by the same transaction")
  void the_completing_effect_is_closed() {
    transition.apply(AGENT, new Input.BacklogUpdated(), null, null);
    EffectStore.Attempted take = effects.attempt(TYPE, 100, Instant.now(), TIMEOUT).get(0);

    transition.apply(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), take.id(), null);

    assertThat(effects.attempt(TYPE, 10, Instant.now().plus(Duration.ofHours(1)), TIMEOUT))
        .noneMatch(effect -> effect.id().equals(take.id()));
  }

  /**
   * A parked call's deadline is written straight to the ROW that was already RUNNING for it --
   * {@code EffectStore#park} -- and read back through the exact same query a poller uses: {@link
   * EffectStore#attempt}. There is no second mechanism to ask, which is the whole point; these
   * tests would fail for the right reason if {@code Transition} stopped calling {@code park} (the
   * row would come due on its OLD, short watchdog instead of its real term) or if it called it with
   * the wrong coordinates (the row would never come due at all).
   */
  @Nested
  @DisplayName("a parked call's own deadline")
  class ParkedDeadline {

    @Test
    @DisplayName("is not due before its term, even once its original watchdog would have lapsed")
    void is_not_due_before_its_term() {
      CallId callId = CallId.of("call-1");
      Instant now = Instant.now();
      Instant term = now.plus(Duration.ofMinutes(30));
      EffectStore.Attempted askEffect = askApproverRow(callId);

      transition.apply(AGENT, new Input.ToolParked(callId, term), null, null);

      // Well past the ORIGINAL watchdog attempt() armed above (TIMEOUT, one minute) but still
      // short of the 30-minute term ToolParked just granted -- due here is what park() failing
      // to run, or running against the wrong row, would look like. Matched by id, not emptiness:
      // askApproverRow's own setup leaves OTHER rows (TakeWork, Remember.Input, CallModel)
      // outstanding too, and this call's own row is the only one this property is about.
      assertThat(effects.attempt(TYPE, 100, now.plus(Duration.ofMinutes(5)), TIMEOUT))
          .as("the call's own row, still short of its term")
          .noneMatch(effect -> effect.id().equals(askEffect.id()));
    }

    @Test
    @DisplayName("is due once its term passes, and is told apart from an ordinary timeout")
    void is_due_after_its_term() {
      CallId callId = CallId.of("call-1");
      Instant now = Instant.now();
      Instant term = now.plus(Duration.ofMinutes(30));
      EffectStore.Attempted askEffect = askApproverRow(callId);

      transition.apply(AGENT, new Input.ToolParked(callId, term), null, null);

      List<EffectStore.Attempted> due = effects.attempt(TYPE, 100, term.plusSeconds(1), TIMEOUT);
      assertThat(due)
          .as("the same row -- its own AskApprover payload, never re-decided")
          .anyMatch(effect -> effect.id().equals(askEffect.id()));
      assertThat(due)
          .as(
              "marked as a lapsed TERM, not an ordinary watchdog timeout -- a caller that missed"
                  + " this would re-run the tool while a person or webhook may still answer it")
          .filteredOn(effect -> effect.id().equals(askEffect.id()))
          .allMatch(EffectStore.Attempted::parked);
    }

    /** Gets an {@code AskApprover} row to RUNNING, the only status {@code park} matches. */
    private EffectStore.Attempted askApproverRow(CallId callId) {
      transition.apply(AGENT, new Input.BacklogUpdated(), null, null);
      transition.apply(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), null, null);
      transition.apply(
          AGENT,
          new Input.ModelAnswered.Asked(
              List.of(new Input.CallSummary(callId, "some-tool")), Usage.unreported()),
          null,
          null);
      List<EffectStore.Attempted> askEffects =
          effects.attempt(TYPE, 100, Instant.now(), TIMEOUT).stream()
              .filter(
                  effect ->
                      EffectStore.PAYLOADS.decode(effect.payload()) instanceof Effect.AskApprover)
              .toList();
      assertThat(askEffects).as("the ask-approver row this test relies on existing").hasSize(1);
      return askEffects.get(0);
    }
  }

  /**
   * The property that matters most: however a call ends, its effect row goes with it. Without this,
   * a tool that defers for three days and then answers gets re-run once its (unrelated,
   * already-discharged-by-answer) term passes -- the exact bug two disagreeing deadline mechanisms
   * used to cause. Each of these would fail for the right reason if {@code Transition} stopped
   * diffing the call maps (see {@code settledCalls}): the row would still be sitting there for a
   * later {@code attempt()} to find.
   */
  @Nested
  @DisplayName("a call that settles, by any route, leaves no effect row behind")
  class Discharge {

    @Test
    @DisplayName("an answer that finally arrives discharges the parked call's own row")
    void an_answer_discharges_it() {
      CallId callId = CallId.of("call-1");
      EffectStore.Attempted askEffect = park(callId);

      transition.apply(AGENT, new Input.ToolCompleted(callId), null, null);

      assertGone(askEffect);
    }

    @Test
    @DisplayName("a denial from a desk discharges the parked call's own row")
    void a_denial_discharges_it() {
      CallId callId = CallId.of("call-1");
      EffectStore.Attempted askEffect = park(callId);

      transition.apply(
          AGENT,
          new Input.ApprovalGiven(callId, "some-tool", ApprovalResult.denied("not tonight")),
          null,
          null);

      assertGone(askEffect);
    }

    @Test
    @DisplayName("a lapsed term discharges the parked call's own row")
    void a_lapsed_term_discharges_it() {
      CallId callId = CallId.of("call-1");
      EffectStore.Attempted askEffect = park(callId);

      transition.apply(AGENT, new Input.DeadlinePassed(callId), null, null);

      assertGone(askEffect);
    }

    /** Puts one call's {@code AskApprover} row into PARKED, RUNNING for a good long while. */
    private EffectStore.Attempted park(CallId callId) {
      transition.apply(AGENT, new Input.BacklogUpdated(), null, null);
      transition.apply(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), null, null);
      transition.apply(
          AGENT,
          new Input.ModelAnswered.Asked(
              List.of(new Input.CallSummary(callId, "some-tool")), Usage.unreported()),
          null,
          null);
      List<EffectStore.Attempted> askEffects =
          effects.attempt(TYPE, 100, Instant.now(), TIMEOUT).stream()
              .filter(
                  effect ->
                      EffectStore.PAYLOADS.decode(effect.payload()) instanceof Effect.AskApprover)
              .toList();
      assertThat(askEffects).as("the ask-approver row this test relies on existing").hasSize(1);
      transition.apply(
          AGENT, new Input.ToolParked(callId, Instant.now().plus(Duration.ofDays(3))), null, null);
      return askEffects.get(0);
    }

    private void assertGone(EffectStore.Attempted askEffect) {
      assertThat(effects.attempt(TYPE, 100, Instant.now().plus(Duration.ofDays(4)), TIMEOUT))
          .as("the settled call's own effect row is gone, not merely re-labelled")
          .noneMatch(effect -> effect.id().equals(askEffect.id()));
    }
  }

  @Test
  @DisplayName("the observability carrier is stamped on every durable effect a decision emits")
  void observability_round_trips_through_the_stored_effect() {
    transition.apply(AGENT, new Input.BacklogUpdated(), null, "traceparent=00-abc-def-01");

    List<EffectStore.Attempted> pending = effects.attempt(TYPE, 100, Instant.now(), TIMEOUT);

    assertThat(pending).isNotEmpty();
    assertThat(pending)
        .allMatch(effect -> "traceparent=00-abc-def-01".equals(effect.observability()));
  }

  @Test
  @DisplayName("a failure partway through the transaction leaves nothing durable behind")
  void a_partial_failure_leaves_no_partial_commit() {
    DataSource exploding = new ExplodingDataSource(database, "INSERT INTO nessy_effect");
    Transition fragile =
        new Transition(
            TYPE,
            new AgentStore(exploding),
            new EffectStore(exploding),
            new TransactionTemplate(new DataSourceTransactionManager(exploding)));

    assertThatThrownBy(() -> fragile.apply(AGENT, new Input.BacklogUpdated(), null, null))
        .isInstanceOf(DataAccessException.class);

    // Read through the GOOD transition, on the GOOD database: if the state write from the failed
    // attempt had survived (a partial commit), this agent would already be AwaitingWork rather
    // than a row that has never been touched.
    assertThat(transition.read(AGENT).phase()).isInstanceOf(Phase.Idle.class);
    assertThat(effects.attempt(TYPE, 100, Instant.now(), TIMEOUT)).isEmpty();
  }

  /**
   * A {@link DataSource} that behaves exactly like the one it wraps, except that ONE statement --
   * picked by a fragment of its SQL text -- throws instead of preparing.
   *
   * <p>Built with {@link Proxy} rather than a mocking library, per house rule: {@code Proxy} is a
   * JDK class, not a library, and this project already hand-writes its fakes. This is what makes
   * the atomicity claim in {@code Transition}'s javadoc falsifiable rather than merely plausible:
   * without it, every test above only ever observes a transaction that fully succeeded, and four
   * small transactions that all happen to succeed look identical to one.
   */
  private static final class ExplodingDataSource implements DataSource {

    private final DataSource delegate;
    private final String trigger;

    ExplodingDataSource(DataSource delegate, String trigger) {
      this.delegate = delegate;
      this.trigger = trigger;
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection real = delegate.getConnection();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName())
                    && args != null
                    && args.length > 0
                    && args[0] instanceof String sql
                    && sql.contains(trigger)) {
                  throw new SQLException("simulated failure preparing: " + sql);
                }
                try {
                  return method.invoke(real, args);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
              });
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return delegate.getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
      delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return delegate.isWrapperFor(iface);
    }
  }
}
