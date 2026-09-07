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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A model call that never even starts streaming.
 *
 * <p>A provider client can throw before it produces a single event — a bad request, a closed
 * connection pool, a serialization bug in the request builder. {@code EffectWorker.callModel} runs
 * the whole call, request-building included, on the blocking executor precisely so a synchronous
 * throw there is caught the same way a failure mid-stream would be.
 *
 * <p>{@code CallModel} is engine-owned, so a provider that throws on EVERY attempt is retried
 * against {@link RetryPolicy} exactly like {@code TakeWork} (design of record 2026-09-04, Task 7,
 * I4) -- it does not fold {@code Input.ModelFailed} and end the turn; it retries, then abandons the
 * obligation with a reason recorded on the row and a loud log line, folding nothing. The backlog
 * read that started this turn DID succeed, so unlike a {@code TakeWork} exhaustion, {@code
 * TurnStarted} IS narrated -- what never arrives is a {@code TurnEnded}, because there is no path
 * left that produces one.
 */
@DisplayName("A model provider that throws before it streams anything")
class ModelStreamFailureTest {

  private static final AgentType WATCHMAN = AgentType.of("brokenmodel");

  private static Engines.Parts parts;

  private static Model throwing() {
    return new Model() {
      @Override
      public ModelId id() {
        return ModelId.of("broken");
      }

      @Override
      public ModelStream stream(ModelRequest request) {
        throw new IllegalStateException("connection pool exhausted");
      }
    };
  }

  @BeforeAll
  static void start() {
    parts = Engines.of(WATCHMAN, throwing());
  }

  @AfterAll
  static void stop() {
    parts.close();
  }

  @Test
  @DisplayName(
      "a persistently throwing model is retried, then the call is abandoned -- the turn never ends,"
          + " and it never spins forever either")
  void a_persistently_throwing_model_call_is_retried_then_abandoned() {
    AgentId agentId = AgentId.of("house-brokenmodel");
    Engines.observe(parts, agentId, new HouseEvent("porch", "bell"));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(() -> assertThat(statusOf(agentId)).isEqualTo("FAILED"));
    assertThat(reasonOf(agentId)).contains("gave up after");

    assertThat(parts.narrated().of(agentId))
        .as("the backlog read succeeded, so the turn DID start")
        .anyMatch(AgentEvent.TurnStarted.class::isInstance)
        .as("CallModel exhaustion folds nothing (I4) -- no TurnEnded ever arrives")
        .noneMatch(AgentEvent.TurnEnded.class::isInstance);
  }

  private static String statusOf(AgentId agentId) {
    return JdbcClient.create(parts.dataSource())
        .sql("SELECT status FROM nessy_effect WHERE agent_id = ? AND status = 'FAILED'")
        .param(agentId.value())
        .query(String.class)
        .single();
  }

  private static String reasonOf(AgentId agentId) {
    return JdbcClient.create(parts.dataSource())
        .sql("SELECT reason FROM nessy_effect WHERE agent_id = ? AND status = 'FAILED'")
        .param(agentId.value())
        .query(String.class)
        .single();
  }
}
