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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.agent.AgentLogic;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Decision;
import org.jwcarman.nessy.engine.agent.Effect;
import org.jwcarman.nessy.engine.agent.Input;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lock the agent, fold, persist, commit -- and nothing else, ever.
 *
 * <p><b>No external I/O inside.</b> The lock is held for the fold alone, which is microseconds; a
 * model call inside it would make every other transition for that agent wait on a network round
 * trip, and a crash would hold the row until the database noticed.
 *
 * <p><b>One transaction, four writes.</b> State, alarms, new effects, and the discharge of the
 * effect that caused this input all commit together. That is the property the whole design rests
 * on: an obligation cannot exist without its cause, and cannot outlive being met.
 *
 * <p><b>A TransactionTemplate rather than {@code @Transactional}.</b> Same guarantees, and this
 * class is constructible in a test without a Spring context -- which matters when the property
 * under test IS the transaction. It also sidesteps self-invocation, where an annotated method
 * called from inside the same object silently runs with no transaction at all.
 */
final class Transition {

  /**
   * What a transition produced for its caller: the state it left behind, and the narration to
   * deliver now that it has committed.
   *
   * <p>Effects are deliberately not here. They are rows, and the caller claims them rather than
   * being handed them -- which is what lets another node pick them up if this one dies between
   * commit and claim.
   */
  record Applied(AgentState next, List<Effect> narrations) {
    Applied {
      narrations = List.copyOf(narrations);
    }
  }

  private final AgentType agentType;
  private final AgentStore store;
  private final EffectStore effects;
  private final Reminders reminders;
  private final TransactionTemplate transactions;

  Transition(
      AgentType agentType,
      AgentStore store,
      EffectStore effects,
      Reminders reminders,
      TransactionTemplate transactions) {
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.effects = Objects.requireNonNull(effects, "effects must not be null");
    this.reminders = Objects.requireNonNull(reminders, "reminders must not be null");
    this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
  }

  /**
   * What this input made of this agent.
   *
   * @param completing the effect whose outcome this input IS, discharged here so that recording the
   *     outcome and retiring the obligation cannot come apart; null when the input arrived from
   *     outside, as an observation or a person's answer does
   * @param observability the W3C propagation carrier to stamp on every effect this decision emits,
   *     so a turn stays one trace after its work is picked up by a different node; null when there
   *     is no ambient trace. Stored verbatim and never parsed -- see nessy_effect.observability.
   */
  Applied apply(AgentId agentId, Input input, EffectId completing, String observability) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(input, "input must not be null");
    return transactions.execute(
        status -> {
          AgentState current = store.lockAndLoad(agentType, agentId);
          Decision decision = AgentLogic.decide(current, input);
          // Five of the logic's answers are "I have already had this news". Persisting an
          // identical document costs a write and a version bump for nothing, and a busy agent is
          // nudged on every observation it is offered -- so this is the common path.
          if (!decision.next().equals(current)) {
            store.save(agentType, agentId, decision.next());
          }
          if (completing != null) {
            effects.complete(completing);
          }
          return new Applied(decision.next(), record(agentId, decision, observability));
        });
  }

  /** The stored state, with no fold and no write. */
  AgentState read(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    return transactions.execute(status -> store.lockAndLoad(agentType, agentId));
  }

  /**
   * Sorts a decision's effects into the three things they are, keeping their order.
   *
   * <p>The ordinal is the effect's position in the decision, not its position among the durable
   * ones -- so a narration between two effects leaves a gap, and the effects still sort correctly.
   * Cheaper than renumbering, and it means the ordinal points back at the decision.
   */
  private List<Effect> record(AgentId agentId, Decision decision, String observability) {
    List<Effect> narrations = new ArrayList<>();
    List<Effect> then = decision.then();
    for (int ordinal = 0; ordinal < then.size(); ordinal++) {
      Effect effect = then.get(ordinal);
      switch (Disposition.of(effect)) {
        case TRANSACTIONAL -> alarm(agentId, effect);
        case NARRATION -> narrations.add(effect);
        case DURABLE ->
            effects.insert(
                agentType,
                agentId,
                decision.next().turnId(),
                ordinal,
                EffectStore.PAYLOADS.encode(effect),
                observability);
      }
    }
    return narrations;
  }

  private void alarm(AgentId agentId, Effect effect) {
    switch (effect) {
      case Effect.SetAlarm(var callId, var expiresAt) ->
          reminders.remind(agentType, agentId, callId, expiresAt);
      case Effect.CancelAlarm(var callId) -> reminders.cancel(agentType, agentId, callId);
      default -> throw new IllegalStateException("not a transactional effect: " + effect);
    }
  }
}
