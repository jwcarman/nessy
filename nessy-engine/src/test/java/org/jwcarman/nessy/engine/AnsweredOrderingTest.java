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
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;

/**
 * Whether the closing line beats the answer it closes over.
 *
 * <p>No tool call anywhere in this turn -- the model answers directly, on its first reply. That is
 * deliberate: a tool-call scenario exercises this ordering only as a side effect of a bigger
 * scenario, which is what let the inversion this pins hide inside {@link ToolCallTest} for as long
 * as it did. Here it is the whole test.
 */
@DisplayName("Answered against TurnEnded, in the order a watcher sees them")
class AnsweredOrderingTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");

  private static Engines.Parts parts;

  @BeforeAll
  static void wire() {
    parts =
        Engines.of(
            WATCHMAN,
            Engines.saying(
                List.of(
                    new ModelResult.Answered(
                        new AnswerMessage(List.of(new TextBlock("noted"))),
                        StopReason.END_TURN,
                        new Usage(1, 1)))));
  }

  @AfterAll
  static void stop() {
    if (parts != null) {
      parts.close();
    }
  }

  @Test
  @DisplayName("the model's answer narrates before the turn that closes over it")
  void answered_arrives_before_the_turn_it_closes_ends() {
    AgentId agentId = AgentId.of("house-answered-ordering");

    Engines.observe(parts, agentId, new HouseEvent("kitchen", "door opened"));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              List<AgentEvent> narrated = parts.narrated().of(agentId);
              assertThat(narrated).isNotEmpty();
              assertThat(narrated).anyMatch(AgentEvent.TurnEnded.class::isInstance);
            });

    List<AgentEvent> narrated = parts.narrated().of(agentId);
    int answeredAt = indexOfFirst(narrated, AgentEvent.Answered.class);
    int turnEndedAt = indexOfFirst(narrated, AgentEvent.TurnEnded.class);

    assertThat(answeredAt).isNotEqualTo(-1);
    assertThat(turnEndedAt).isNotEqualTo(-1);
    assertThat(answeredAt).isLessThan(turnEndedAt);
  }

  private static int indexOfFirst(List<AgentEvent> events, Class<? extends AgentEvent> type) {
    for (int index = 0; index < events.size(); index++) {
      if (type.isInstance(events.get(index))) {
        return index;
      }
    }
    return -1;
  }
}
