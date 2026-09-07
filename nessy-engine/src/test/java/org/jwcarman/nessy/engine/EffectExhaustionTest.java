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
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Effect;
import org.jwcarman.nessy.engine.agent.Input;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * I4: exhaustion of an engine-owned effect abandons its row and folds NOTHING -- it neither
 * dispatches {@code Input.ModelFailed} nor invents a new {@link Input} arm to describe the
 * exhaustion. Before this, {@code giveUp} on a {@code TakeWork} dispatched {@code ModelFailed},
 * {@code endTurn} answered with a FRESH {@code TakeWork} at {@code attempts=0}, and an unreadable
 * backlog row looped forever narrating turns that never started.
 */
@DisplayName("An exhausted engine-owned effect")
class EffectExhaustionTest {

  private static ActorTestKit testKit;

  @BeforeAll
  static void start() {
    testKit = ClusterOfOne.start();
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  private record Captured(AgentId agentId, Input input) {}

  @Test
  @DisplayName(
      "an exhausted TakeWork leaves exactly one abandoned row, emits no further effects, and folds"
          + " nothing into the agent")
  void an_exhausted_take_work_abandons_and_folds_nothing() {
    AgentType type = AgentType.of("exhausted-take-work");
    List<Captured> seen = new ArrayList<>();
    Engines.Parts parts =
        Engines.of(
            testKit.system(),
            type,
            Engines.stalled(),
            List.of(),
            Runnable::run,
            (agentId, input, completing, observability) -> seen.add(new Captured(agentId, input)));
    AgentId agentId = AgentId.of("house-exhausted-take-work");
    EffectId effectId =
        parts
            .effects()
            .insert(
                type,
                agentId,
                null,
                null,
                0,
                EffectStore.PAYLOADS.encode(new Effect.TakeWork()),
                null);
    parts.effects().attempt(type, 100, Instant.now(), Duration.ofMinutes(1));

    // Engines' default RetryPolicy.exponential(..., maxAttempts=3) answers GiveUp once
    // attemptsMade >= 3 -- see EffectWorker#perform's top-of-method policy consultation.
    parts
        .effectWorker()
        .perform(agentId, AgentState.idle(), null, new Effect.TakeWork(), effectId, 3);

    assertThat(seen)
        .as("no Input was dispatched -- nothing folds for an exhausted TakeWork")
        .isEmpty();
    assertThat(statusOf(parts, effectId)).isEqualTo("FAILED");
    assertThat(rowCountFor(parts, agentId)).as("no further effect was emitted").isEqualTo(1);
  }

  private String statusOf(Engines.Parts parts, EffectId effectId) {
    return JdbcClient.create(parts.dataSource())
        .sql("SELECT status FROM nessy_effect WHERE effect_id = ?")
        .param(effectId.value())
        .query(String.class)
        .single();
  }

  private int rowCountFor(Engines.Parts parts, AgentId agentId) {
    Integer count =
        JdbcClient.create(parts.dataSource())
            .sql("SELECT count(*) FROM nessy_effect WHERE agent_id = ?")
            .param(agentId.value())
            .query(Integer.class)
            .single();
    return count == null ? 0 : count;
  }
}
