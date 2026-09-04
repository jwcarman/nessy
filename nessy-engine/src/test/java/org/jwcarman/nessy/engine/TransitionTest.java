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
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.model.Usage;
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
  private static final Instant SOON = Instant.now().plus(Duration.ofMinutes(1));

  private EmbeddedDatabase database;
  private AgentStore store;
  private EffectStore effects;
  private Reminders reminders;
  private Transition transition;

  @BeforeEach
  void fresh() {
    database = TestDatabase.fresh();
    store = new AgentStore(database);
    effects = new EffectStore(database);
    reminders = new Reminders(database);
    transition =
        new Transition(
            TYPE,
            store,
            effects,
            reminders,
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

    List<EffectStore.Claimed> pending = effects.claim(TYPE, AGENT, SOON);

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
    effects.claim(TYPE, AGENT, SOON);
    transition.apply(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), null, null);

    List<EffectStore.Claimed> pending = effects.claim(TYPE, AGENT, SOON);

    assertThat(pending).isNotEmpty();
    assertThat(pending)
        .isSortedAccordingTo(java.util.Comparator.comparingInt(EffectStore.Claimed::ordinal));
    assertThat(EffectStore.PAYLOADS.decode(pending.get(0).payload()))
        .isInstanceOf(Effect.Remember.Input.class);
  }

  @Test
  @DisplayName("narration comes back to be delivered, and is never a row")
  void narration_is_returned_not_stored() {
    transition.apply(AGENT, new Input.BacklogUpdated(), null, null);
    effects.claim(TYPE, AGENT, SOON);

    Transition.Applied started =
        transition.apply(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), null, null);

    assertThat(started.narrations()).isNotEmpty();
    assertThat(started.narrations()).allMatch(Effect.Narrate.class::isInstance);
    List<EffectStore.Claimed> pending = effects.claim(TYPE, AGENT, SOON);
    assertThat(pending).isNotEmpty();
    assertThat(pending)
        .noneMatch(
            effect -> EffectStore.PAYLOADS.decode(effect.payload()) instanceof Effect.Narrate);
  }

  @Test
  @DisplayName("a decision that changes nothing writes nothing")
  void an_unchanged_decision_is_not_persisted() {
    transition.apply(AGENT, new Input.BacklogUpdated(), null, null);
    effects.claim(TYPE, AGENT, SOON);

    Transition.Applied repeat = transition.apply(AGENT, new Input.BacklogUpdated(), null, null);

    assertThat(repeat.narrations()).isEmpty();
    assertThat(effects.claim(TYPE, AGENT, SOON)).isEmpty();
    assertThat(repeat.next().phase()).isInstanceOf(Phase.AwaitingWork.class);
  }

  @Test
  @DisplayName("the effect that produced this input is discharged by the same transaction")
  void the_completing_effect_is_closed() {
    transition.apply(AGENT, new Input.BacklogUpdated(), null, null);
    EffectStore.Claimed take = effects.claim(TYPE, AGENT, SOON).get(0);

    transition.apply(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), take.id(), null);

    assertThat(effects.claimExpired(TYPE, Instant.now().plus(Duration.ofHours(1)), 10))
        .noneMatch(effect -> effect.id().equals(take.id()));
  }

  @Test
  @DisplayName("a parked call arms a live alarm, and settling it disarms the same one")
  void alarms_are_armed_and_disarmed_by_the_right_effect() {
    CallId callId = CallId.of("call-1");

    transition.apply(AGENT, new Input.BacklogUpdated(), null, null);
    transition.apply(AGENT, new Input.WorkTaken(TurnId.of("turn-1"), "claim-1"), null, null);
    transition.apply(
        AGENT,
        new Input.ModelAnswered.Asked(
            List.of(new Input.CallSummary(callId, "some-tool")), Usage.unreported()),
        null,
        null);

    // TRANSACTIONAL, not a row: Effect.SetAlarm never reaches nessy_effect, so the only way to
    // observe it is to ask Reminders whether it armed the RIGHT half of the pair. A swap of
    // remind/cancel inside Transition.alarm fails this line: nothing would be armed.
    transition.apply(AGENT, new Input.ToolParked(callId, SOON), null, null);
    assertThat(reminders.find(TYPE, AGENT, callId)).isPresent();

    // And a swap fails this line the other way: CancelAlarm would have called remind again
    // instead of cancel, and the alarm set above would still be sitting there.
    transition.apply(AGENT, new Input.ToolCompleted(callId), null, null);
    assertThat(reminders.find(TYPE, AGENT, callId)).isEmpty();
  }

  @Test
  @DisplayName("the observability carrier is stamped on every durable effect a decision emits")
  void observability_round_trips_through_the_stored_effect() {
    transition.apply(AGENT, new Input.BacklogUpdated(), null, "traceparent=00-abc-def-01");

    List<EffectStore.Claimed> pending = effects.claim(TYPE, AGENT, SOON);

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
            new Reminders(exploding),
            new TransactionTemplate(new DataSourceTransactionManager(exploding)));

    assertThatThrownBy(() -> fragile.apply(AGENT, new Input.BacklogUpdated(), null, null))
        .isInstanceOf(DataAccessException.class);

    // Read through the GOOD transition, on the GOOD database: if the state write from the failed
    // attempt had survived (a partial commit), this agent would already be AwaitingWork rather
    // than a row that has never been touched.
    assertThat(transition.read(AGENT).phase()).isInstanceOf(Phase.Idle.class);
    assertThat(effects.claim(TYPE, AGENT, SOON)).isEmpty();
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
