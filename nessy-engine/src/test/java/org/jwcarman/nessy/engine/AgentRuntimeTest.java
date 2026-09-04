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
import org.jwcarman.nessy.api.TurnId;
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
            (agentId, state, effect, effectId) -> performed.add(effect),
            sameThread,
            Duration.ofMinutes(1));
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
  @DisplayName("the state is committed before any obligation runs")
  void the_transition_commits_first() {
    List<Phase> phaseWhenPerformed = new ArrayList<>();
    AgentRuntime observing =
        new AgentRuntime(
            TYPE,
            transition,
            effects,
            (agentId, state, effect, effectId) -> phaseWhenPerformed.add(readPhase()),
            Runnable::run,
            Duration.ofMinutes(1));

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
            (agentId, state, effect, effectId) -> {
              throw new IllegalStateException("this node just died");
            },
            Runnable::run,
            Duration.ofMillis(-1));

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

  private Phase readPhase() {
    return new TransactionTemplate(new DataSourceTransactionManager(database))
        .execute(status -> store.lockAndLoad(TYPE, AGENT))
        .phase();
  }
}
