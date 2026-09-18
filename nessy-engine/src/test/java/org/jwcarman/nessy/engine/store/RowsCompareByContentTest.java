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
package org.jwcarman.nessy.engine.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;

/** Two records carry byte[]; each compares, hashes and prints by content, as a record would not. */
@DisplayName("A row holding bytes")
class RowsCompareByContentTest {

  private static final UUID ID = UUID.randomUUID();
  private static final Instant AT = Instant.parse("2026-09-15T12:00:00Z");

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void an_agent_state_row_is_equal_to_one_with_the_same_content() {
    AgentStateRow one = new AgentStateRow(ID, "chat", 3, "Idle", bytes("{}"), AT);
    AgentStateRow same = new AgentStateRow(ID, "chat", 3, "Idle", bytes("{}"), AT);
    AgentStateRow other = new AgentStateRow(ID, "chat", 4, "Idle", bytes("{}"), AT);

    assertThat(one).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(other);
    assertThat(one.equals("a string")).isFalse();
    assertThat(one.toString()).contains("chat").contains("version=3").contains("2 bytes");
    assertThat(one.seq()).isEqualTo(3);
    assertThat(AgentStateRow.initial(ID, "chat", "Idle", bytes("{}"), AT).version()).isZero();
    assertThat(one.folded("Inferring", bytes("{ }"), AT.plusSeconds(1)).stateType())
        .isEqualTo("Inferring");
  }

  @Test
  void an_attempt_is_equal_to_one_with_the_same_content() {
    AgentId agent = new AgentId(UUID.randomUUID());
    Attempt one = new Attempt(ID, agent, bytes("effect"), bytes("failure"), 1, AT, "00-trace");
    Attempt same = new Attempt(ID, agent, bytes("effect"), bytes("failure"), 1, AT, "00-trace");
    Attempt other = new Attempt(ID, agent, bytes("effect"), bytes("failure"), 2, AT, "00-trace");

    assertThat(one).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(other);
    assertThat(one.equals(null)).isFalse();
    assertThat(one.toString()).contains("6 bytes").contains("7 bytes").contains("attemptsMade=1");
    assertThat(new Attempt(ID, agent, null, null, 0, AT, null).toString()).contains("0 bytes");
  }

  @Test
  void every_field_takes_part_in_equality() {
    AgentId agent = new AgentId(UUID.randomUUID());
    AgentStateRow row = new AgentStateRow(ID, "chat", 3, "Idle", bytes("{}"), AT);
    assertThat(row)
        .isNotEqualTo(new AgentStateRow(UUID.randomUUID(), "chat", 3, "Idle", bytes("{}"), AT))
        .isNotEqualTo(new AgentStateRow(ID, "other", 3, "Idle", bytes("{}"), AT))
        .isNotEqualTo(new AgentStateRow(ID, "chat", 3, "Inferring", bytes("{}"), AT))
        .isNotEqualTo(new AgentStateRow(ID, "chat", 3, "Idle", bytes("[]"), AT))
        .isNotEqualTo(new AgentStateRow(ID, "chat", 3, "Idle", bytes("{}"), AT.plusSeconds(1)));

    Attempt attempt = new Attempt(ID, agent, bytes("e"), bytes("f"), 1, AT, "t");
    assertThat(attempt)
        .isNotEqualTo(new Attempt(UUID.randomUUID(), agent, bytes("e"), bytes("f"), 1, AT, "t"))
        .isNotEqualTo(
            new Attempt(ID, new AgentId(UUID.randomUUID()), bytes("e"), bytes("f"), 1, AT, "t"))
        .isNotEqualTo(new Attempt(ID, agent, bytes("x"), bytes("f"), 1, AT, "t"))
        .isNotEqualTo(new Attempt(ID, agent, bytes("e"), bytes("x"), 1, AT, "t"))
        .isNotEqualTo(new Attempt(ID, agent, bytes("e"), bytes("f"), 1, AT.plusSeconds(1), "t"))
        .isNotEqualTo(new Attempt(ID, agent, bytes("e"), bytes("f"), 1, AT, "u"))
        .isNotEqualTo("not an attempt");
  }
}
