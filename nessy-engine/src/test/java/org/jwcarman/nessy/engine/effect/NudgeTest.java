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
package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/**
 * Work this process writes down is taken now, not at the next poll.
 *
 * <p>The poll here is ten minutes, and the first one waits a whole interval, so a turn that
 * finishes inside the test was finished by the nudge the fold gave its dispatcher -- polling alone
 * would not have looked yet.
 */
class NudgeTest {

  @Test
  void a_turn_runs_without_waiting_for_the_poll() {
    AgentType type = new AgentType("nudged");
    AgentId agentId = new AgentId(UUID.randomUUID());

    try (EngineFixture engine =
        new EngineFixture(
            (_, _) -> new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("done")))) {
      engine
          .harnesses()
          .create(
              String.class,
              config ->
                  config
                      .agentType(type)
                      .systemPrompt("You are a test assistant.")
                      .inference(in -> in.model("a-model"))
                      .effects(e -> e.pollInterval(Duration.ofMinutes(10))))
          .observe(agentId, "hello");

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> assertThat(engine.history().entriesFrom(type, agentId, 0)).hasSize(2));
    }
  }
}
