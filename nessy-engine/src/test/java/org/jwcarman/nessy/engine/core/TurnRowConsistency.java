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
package org.jwcarman.nessy.engine.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.jwcarman.nessy.backend.turn.AgentTurn;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks that a turn row's JSON and its counts tell the same story: the trajectory's outcome, round
 * count, and call tallies must equal the columns beside it.
 */
public final class TurnRowConsistency {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private TurnRowConsistency() {}

  public static void assertConsistent(AgentTurn row) {
    JsonNode json = MAPPER.readTree(row.trajectoryJson());
    assertThat(json.get("outcome").asString()).isEqualTo(row.outcome().name());
    JsonNode rounds = json.get("rounds");
    assertThat(rounds.size()).isEqualTo(row.rounds());
    int calls = 0;
    int successes = 0;
    int failures = 0;
    int denials = 0;
    for (JsonNode round : rounds) {
      for (JsonNode entry : round) {
        calls++;
        switch (entry.get("outcome").asString()) {
          case "SUCCESS" -> successes++;
          case "FAILED" -> failures++;
          case "DENIED" -> denials++;
          default -> throw new AssertionError("unknown call outcome in " + row.trajectoryJson());
        }
      }
    }
    assertThat(calls).as("tool calls").isEqualTo(row.toolCalls());
    assertThat(successes).as("successes").isEqualTo(row.toolSuccesses());
    assertThat(failures).as("failures").isEqualTo(row.toolFailures());
    assertThat(denials).as("denials").isEqualTo(row.toolDenials());
  }
}
