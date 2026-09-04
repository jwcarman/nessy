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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.agent.Effect;
import org.jwcarman.nessy.engine.agent.Input;
import org.jwcarman.nessy.engine.agent.Phase;
import org.jwcarman.nessy.testing.TestDatabase;
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
    assertThat(effects.claim(TYPE, AGENT, SOON))
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
}
