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
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnResult;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * A model call that never even starts streaming.
 *
 * <p>A provider client can throw before it produces a single event — a bad request, a closed
 * connection pool, a serialization bug in the request builder. {@code EffectWorker.callModel} runs
 * the whole call, request-building included, on the blocking executor precisely so a synchronous
 * throw there is caught the same way a failure mid-stream would be.
 *
 * <p>{@code CallModel} is engine-owned, so a throw that keeps happening is retried against {@link
 * RetryPolicy} rather than folded on the first attempt -- but unlike {@code TakeWork}, its
 * exhaustion still ends the turn as {@link TurnResult.Failed}, carrying the provider's own message
 * (design of record 2026-09-04, Task 7's I4, corrected: the loop I4 exists to prevent is {@code
 * TakeWork}-specific, not a property every engine-owned effect shares -- see {@code
 * EffectWorker#giveUp}'s javadoc). The message it carries is the LAST real attempt's, read off the
 * effect row before it is abandoned, not a generic "gave up after N failures".
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
  @DisplayName("the turn closes as failed, carrying the provider's own message")
  void the_turn_reports_the_thrown_message_as_its_failure_reason() {
    AgentId agentId = AgentId.of("house-brokenmodel");
    Engines.observe(parts, agentId, new HouseEvent("porch", "bell"));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              List<AgentEvent.TurnEnded> ended =
                  parts.narrated().of(agentId).stream()
                      .filter(AgentEvent.TurnEnded.class::isInstance)
                      .map(AgentEvent.TurnEnded.class::cast)
                      .toList();
              assertThat(ended).hasSize(1);
              assertThat(ended.getFirst().outcome()).isInstanceOf(TurnResult.Failed.class);
              TurnResult.Failed failed = (TurnResult.Failed) ended.getFirst().outcome();
              assertThat(failed.reason()).isEqualTo("connection pool exhausted");
            });
  }
}
