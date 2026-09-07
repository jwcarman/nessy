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

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.engine.agent.Input;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A backlog table that cannot be reached when {@code take} runs.
 *
 * <p>Nothing before this test drove a REAL SQL failure through {@code EffectWorker.takeWork} on the
 * durable pipeline: {@code TakeWork} is engine-owned, so a failed attempt is retried against {@link
 * RetryPolicy} exactly like any other engine-owned effect (design of record 2026-09-04, Task 7) --
 * it does not end the turn as {@code Failed}, because the turn never even started (see {@code
 * AgentLogic#onWorkTaken}: {@code TurnStarted} is narrated only once a row is actually taken). What
 * this proves is I4's ruling reached honestly through a real backlog exception rather than a
 * synthetic exhaustion: the obligation retries, then abandons with a reason recorded on the row and
 * a loud log line -- never silently forever, and never a phantom {@code TurnEnded} for a turn that
 * never began.
 *
 * <p>Only the backlog table is dropped. Claims, effects and the agent row all stay reachable, since
 * retrying and finally abandoning the obligation -- the very things this test proves -- themselves
 * need a working database underneath them.
 */
@DisplayName("A backlog the store cannot read")
class BacklogReadFailureTest {

  private static final AgentType WATCHMAN = AgentType.of("unreachable");

  private static Engines.Parts parts;

  @BeforeAll
  static void start() {
    parts =
        Engines.of(
            WATCHMAN,
            Engines.saying(
                List.of(
                    new ModelResult.Answered(
                        new AnswerMessage(List.of(new TextBlock("unreachable"))),
                        StopReason.END_TURN,
                        Usage.unreported()))));
  }

  @AfterAll
  static void stop() {
    parts.close();
  }

  @Test
  @DisplayName(
      "asking for work it cannot read is retried, then abandoned -- never silently forever, and"
          + " never a turn that never started")
  void a_take_that_cannot_reach_the_database_is_retried_then_abandoned() {
    AgentId agentId = AgentId.of("house-unreachable");

    // Down for good, deliberately, and ONLY the table this obligation reads: the point is a
    // `take` that cannot complete, not a database that cannot do anything at all.
    JdbcClient.create(parts.dataSource()).sql("DROP TABLE nessy_backlog").update();

    parts.runtime().dispatch(agentId, new Input.BacklogUpdated());

    await()
        .atMost(15, SECONDS)
        .untilAsserted(() -> assertThat(statusOf(agentId)).isEqualTo("FAILED"));
    assertThat(reasonOf(agentId)).contains("gave up after");
    assertThat(parts.narrated().of(agentId))
        .as("TakeWork exhaustion folds nothing (I4) -- the turn never started, so nothing narrates")
        .isEmpty();
  }

  private static String statusOf(AgentId agentId) {
    return JdbcClient.create(parts.dataSource())
        .sql("SELECT status FROM nessy_effect WHERE agent_id = ?")
        .param(agentId.value())
        .query(String.class)
        .single();
  }

  private static String reasonOf(AgentId agentId) {
    return JdbcClient.create(parts.dataSource())
        .sql("SELECT reason FROM nessy_effect WHERE agent_id = ?")
        .param(agentId.value())
        .query(String.class)
        .single();
  }
}
