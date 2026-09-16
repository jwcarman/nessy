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
}
