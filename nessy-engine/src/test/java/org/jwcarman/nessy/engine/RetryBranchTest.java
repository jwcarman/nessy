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
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Effect;
import org.jwcarman.nessy.engine.agent.Input;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * I1: the retry/giveUp branch of {@code EffectWorker#perform}, driven with a REAL {@code attempts}
 * count -- every other {@code EffectWorker.perform} call in the test tree before this used a
 * 4/5-arg overload hardcoding the {@code -1} sentinel, which turns {@link RetryPolicy} consultation
 * off entirely. That is how C1 shipped: the branch this class exercises had no test at all.
 *
 * <p>{@code CallModel} is the vehicle, deliberately: it is engine-owned (R-AB leaves its retry
 * behavior untouched, unlike {@code AskApprover}/{@code RunTool}), and a model client that throws
 * is the simplest real trigger for {@link EffectWorker}'s own {@code run} failure path.
 */
@DisplayName("EffectWorker's retry/giveUp branch, under a real RetryPolicy consultation")
class RetryBranchTest {

  private record Captured(AgentId agentId, Input input) {}

  @Test
  @DisplayName("a transient failure under budget schedules a retry with the policy's backoff")
  void a_transient_failure_under_budget_schedules_a_retry_with_the_policys_backoff() {
    AgentType type = AgentType.of("retry-under-budget");
    List<Captured> seen = new ArrayList<>();
    Engines.Parts parts =
        Engines.of(
            type,
            throwingModel(),
            List.of(),
            Runnable::run,
            (agentId, input, completing, observability) -> seen.add(new Captured(agentId, input)));
    AgentId agentId = AgentId.of("house-retry-under-budget");
    TurnId turnId = TurnId.of("turn-retry-under-budget");
    EffectId effectId =
        parts
            .effects()
            .insert(
                type,
                agentId,
                turnId,
                null,
                0,
                EffectStore.PAYLOADS.encode(new Effect.CallModel()),
                null);
    parts.effects().attempt(type, 100, Instant.now(), Duration.ofMinutes(1));
    Instant before = Instant.now();

    // Engines' default RetryPolicy.exponential(base=1ms, ..., maxAttempts=3): attemptsMade=0 is
    // well under budget, so this is RetryAfter, never GiveUp.
    parts
        .effectWorker()
        .perform(agentId, AgentState.idle(), turnId, new Effect.CallModel(), effectId, 0);

    assertThat(seen)
        .as("the agent hears nothing about a retryable failure -- that is not its business yet")
        .isEmpty();
    assertThat(statusOf(parts, effectId)).isEqualTo("PENDING");
    assertThat(attemptsOf(parts, effectId)).isEqualTo(1);
    assertThat(actionableAtOf(parts, effectId))
        .as("scheduled for a moment after this attempt, per the policy's backoff")
        .isAfter(before)
        .isBefore(before.plusSeconds(5));
  }

  @Test
  @DisplayName("a failure at budget abandons the row, folds ModelFailed, and no call is made")
  void a_failure_at_budget_abandons_the_row_and_ends_the_turn_failed() {
    AgentType type = AgentType.of("retry-at-budget");
    List<Captured> seen = new ArrayList<>();
    java.util.concurrent.atomic.AtomicBoolean modelCalled =
        new java.util.concurrent.atomic.AtomicBoolean();
    Engines.Parts parts =
        Engines.of(
            type,
            countingModel(modelCalled),
            List.of(),
            Runnable::run,
            (agentId, input, completing, observability) -> seen.add(new Captured(agentId, input)));
    AgentId agentId = AgentId.of("house-retry-at-budget");
    TurnId turnId = TurnId.of("turn-retry-at-budget");
    EffectId effectId =
        parts
            .effects()
            .insert(
                type,
                agentId,
                turnId,
                null,
                0,
                EffectStore.PAYLOADS.encode(new Effect.CallModel()),
                null);
    parts.effects().attempt(type, 100, Instant.now(), Duration.ofMinutes(1));

    // attemptsMade=3 meets Engines' default maxAttempts=3 -- GiveUp, consulted BEFORE any call.
    parts
        .effectWorker()
        .perform(agentId, AgentState.idle(), turnId, new Effect.CallModel(), effectId, 3);

    assertThat(modelCalled)
        .as(
            "giveUp closes the obligation before making the call it would"
                + " otherwise retry one time too many")
        .isFalse();
    // CallModel is the corrected half of I4 (Task 7 fix round): unlike TakeWork, its exhaustion
    // DOES fold -- endTurn never emits another CallModel, so there is no loop to protect against,
    // and folding is what lets the turn actually end as Failed instead of hanging forever.
    assertThat(seen).isNotEmpty();
    assertThat(seen)
        .extracting(Captured::input)
        .singleElement()
        .isInstanceOf(Input.ModelFailed.class)
        .extracting(input -> ((Input.ModelFailed) input).reason())
        .isEqualTo("gave up after 3 failures");
    assertThat(statusOf(parts, effectId)).isEqualTo("FAILED");
    assertThat(reasonOf(parts, effectId)).contains("gave up after 3 failures");
  }

  private String statusOf(Engines.Parts parts, EffectId effectId) {
    return JdbcClient.create(parts.dataSource())
        .sql("SELECT status FROM nessy_effect WHERE effect_id = ?")
        .param(effectId.value())
        .query(String.class)
        .single();
  }

  private int attemptsOf(Engines.Parts parts, EffectId effectId) {
    return JdbcClient.create(parts.dataSource())
        .sql("SELECT attempts FROM nessy_effect WHERE effect_id = ?")
        .param(effectId.value())
        .query(Integer.class)
        .single();
  }

  private String reasonOf(Engines.Parts parts, EffectId effectId) {
    return JdbcClient.create(parts.dataSource())
        .sql("SELECT reason FROM nessy_effect WHERE effect_id = ?")
        .param(effectId.value())
        .query(String.class)
        .single();
  }

  private Instant actionableAtOf(Engines.Parts parts, EffectId effectId) {
    return JdbcClient.create(parts.dataSource())
        .sql("SELECT actionable_at FROM nessy_effect WHERE effect_id = ?")
        .param(effectId.value())
        .query(java.sql.Timestamp.class)
        .single()
        .toInstant();
  }

  private static Model throwingModel() {
    return new Model() {
      @Override
      public ModelId id() {
        return ModelId.of("throwing");
      }

      @Override
      public ModelStream stream(ModelRequest request) {
        throw new IllegalStateException("the model client is down");
      }
    };
  }

  private static Model countingModel(java.util.concurrent.atomic.AtomicBoolean called) {
    return new Model() {
      @Override
      public ModelId id() {
        return ModelId.of("counting");
      }

      @Override
      public ModelStream stream(ModelRequest request) {
        called.set(true);
        return new ModelStream() {
          @Override
          public Iterator<ModelEvent> iterator() {
            return java.util.Collections.emptyIterator();
          }

          @Override
          public void close() {
            // Nothing to release.
          }
        };
      }
    };
  }
}
