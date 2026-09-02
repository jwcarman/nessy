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
package org.jwcarman.nessy.spi.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.store.Schemas;

@DisplayName("What an agent remembers")
class TranscriptMemoryTest {

  /**
   * A database nobody else is using, with Nessy's DDL applied.
   *
   * <p>Built here rather than taken from {@code nessy-testing}, which depends on this module — a
   * test dependency the other way would be a cycle.
   */
  private static javax.sql.DataSource freshDatabase() {
    javax.sql.DataSource database =
        new org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder()
            .setType(org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build();
    Schemas.initialize(database);
    return database;
  }

  private static final AgentId HOUSE = AgentId.of("house-12");
  private TranscriptMemory transcripts;

  @BeforeEach
  void setUp() {
    transcripts = TranscriptMemory.eternal(freshDatabase(), AgentType.of("watchman"));
  }

  /** An exchange: the call, and the answer that settled it. */
  private static ExchangeMessage exchange(String callId) {
    return new ExchangeMessage(
        List.of(
            new ToolCallBlock(
                new ToolCall(
                    CallId.of(callId), "read_file", JsonNodeFactory.instance.objectNode()))),
        List.of(ToolResultBlock.of(CallId.of(callId), ToolResult.ok("done"))));
  }

  @Test
  void a_fresh_agent_remembers_nothing() {
    assertThat(transcripts.recall(HOUSE).messages()).isEmpty();
  }

  @Test
  @DisplayName("messages come back as themselves, in order")
  void messages_round_trip_polymorphically() {
    transcripts.remember(HOUSE, UserMessage.of("hi"));
    transcripts.remember(HOUSE, new AnswerMessage(List.of(new TextBlock("hello"))));

    Context context = transcripts.recall(HOUSE);

    assertThat(context.messages()).hasSize(2);
    assertThat(context.messages().get(0)).isInstanceOf(UserMessage.class);
    assertThat(context.messages().get(1)).isInstanceOf(AnswerMessage.class);
    assertThat(context.lines())
        .containsExactly(new Context.Line("user", "hi"), new Context.Line("assistant", "hello"));
  }

  @Test
  void two_agents_do_not_share_a_transcript() {
    transcripts.remember(HOUSE, UserMessage.of("mine"));

    assertThat(transcripts.recall(AgentId.of("house-13")).messages()).isEmpty();
  }

  @Test
  @DisplayName("an exchange is written whole, so recall is always a valid Context")
  void a_tool_exchange_is_remembered_as_a_pair() {
    transcripts.remember(HOUSE, UserMessage.of("go"));
    transcripts.remember(HOUSE, exchange("c1"));

    Context context = transcripts.recall(HOUSE);

    assertThat(context.messages()).hasSize(2);
    assertThat(context.messages().get(1)).isInstanceOf(ExchangeMessage.class);
  }

  @Nested
  @DisplayName("A budgeted memory")
  class ABudgetedMemory {

    @Test
    @DisplayName("a zero character budget is refused")
    void a_zero_budget_is_refused() {
      DataSource database = freshDatabase();

      assertThatThrownBy(() -> TranscriptMemory.recent(database, AgentType.of("watchman"), 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maxCharacters");
    }

    @Test
    @DisplayName("a negative character budget is refused")
    void a_negative_budget_is_refused() {
      DataSource database = freshDatabase();

      assertThatThrownBy(() -> TranscriptMemory.recent(database, AgentType.of("watchman"), -1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maxCharacters");
    }

    @Test
    @DisplayName(
        "a budget that cuts mid-history keeps the newest messages that fit and forgets the rest")
    void a_budget_that_cuts_mid_history_keeps_only_what_fits() {
      // Three 5-character messages; a budget of 12 fits the newest two (10) but not all three
      // (15), so the walk must stop after the second-newest and never even look at the oldest.
      TranscriptMemory budgeted =
          TranscriptMemory.recent(freshDatabase(), AgentType.of("watchman"), 12);
      budgeted.remember(HOUSE, UserMessage.of("aaaaa"));
      budgeted.remember(HOUSE, UserMessage.of("bbbbb"));
      budgeted.remember(HOUSE, UserMessage.of("ccccc"));

      Context context = budgeted.recall(HOUSE);

      assertThat(context.lines())
          .containsExactly(new Context.Line("user", "bbbbb"), new Context.Line("user", "ccccc"));
    }

    @Test
    @DisplayName(
        "a single message bigger than the whole budget is kept rather than recalling nothing")
    void a_message_larger_than_the_budget_is_still_kept() {
      TranscriptMemory budgeted =
          TranscriptMemory.recent(freshDatabase(), AgentType.of("watchman"), 1);
      budgeted.remember(HOUSE, UserMessage.of("way too big for the budget"));

      Context context = budgeted.recall(HOUSE);

      assertThat(context.lines())
          .containsExactly(new Context.Line("user", "way too big for the budget"));
    }

    @Test
    @DisplayName("a fresh agent under a budget still recalls nothing")
    void a_fresh_agent_under_a_budget_recalls_nothing() {
      TranscriptMemory budgeted =
          TranscriptMemory.recent(freshDatabase(), AgentType.of("watchman"), 100);

      assertThat(budgeted.recall(HOUSE).messages()).isEmpty();
    }
  }

  @Nested
  @DisplayName("Recalling after a sequence number")
  class RecallingAfterASequenceNumber {

    @Test
    @DisplayName("recallAfter(0) means everything, oldest first")
    void recall_after_zero_means_everything() {
      transcripts.remember(HOUSE, UserMessage.of("first"));
      transcripts.remember(HOUSE, UserMessage.of("second"));

      Context context = transcripts.recallAfter(HOUSE, 0);

      assertThat(context.lines())
          .containsExactly(new Context.Line("user", "first"), new Context.Line("user", "second"));
    }

    @Test
    @DisplayName("recallAfter(seq) returns only what came after that sequence number")
    void recall_after_a_seq_skips_the_covered_prefix() {
      transcripts.remember(HOUSE, UserMessage.of("covered"));
      long coveredSeq = transcripts.lastSeq(HOUSE);
      transcripts.remember(HOUSE, UserMessage.of("new"));

      Context context = transcripts.recallAfter(HOUSE, coveredSeq);

      assertThat(context.lines()).containsExactly(new Context.Line("user", "new"));
    }

    @Test
    @DisplayName("recallAfter the newest sequence number returns nothing")
    void recall_after_the_newest_seq_returns_nothing() {
      transcripts.remember(HOUSE, UserMessage.of("only"));

      Context context = transcripts.recallAfter(HOUSE, transcripts.lastSeq(HOUSE));

      assertThat(context.messages()).isEmpty();
    }
  }

  @Nested
  @DisplayName("The last sequence number")
  class TheLastSequenceNumber {

    @Test
    @DisplayName("an agent that never spoke has a last sequence of 0")
    void an_agent_that_never_spoke_has_seq_zero() {
      assertThat(transcripts.lastSeq(HOUSE)).isZero();
    }

    @Test
    @DisplayName("it climbs by one with every remembered message")
    void it_climbs_with_every_message() {
      transcripts.remember(HOUSE, UserMessage.of("one"));
      assertThat(transcripts.lastSeq(HOUSE)).isEqualTo(1L);

      transcripts.remember(HOUSE, UserMessage.of("two"));
      assertThat(transcripts.lastSeq(HOUSE)).isEqualTo(2L);
    }
  }

  @Nested
  @DisplayName("Forgetting")
  class Forgetting {

    @Test
    @DisplayName("forgetting drops everything this agent said")
    void forgetting_drops_everything_the_agent_said() {
      transcripts.remember(HOUSE, UserMessage.of("secret"));

      transcripts.forget(HOUSE);

      assertThat(transcripts.recall(HOUSE).messages()).isEmpty();
    }

    @Test
    @DisplayName("forgetting an agent who never said anything is silent, not an error")
    void forgetting_a_silent_agent_is_silent() {
      transcripts.forget(HOUSE);

      assertThat(transcripts.recall(HOUSE).messages()).isEmpty();
    }

    @Test
    @DisplayName("forgetting one agent leaves another agent's transcript alone")
    void forgetting_one_agent_leaves_another_untouched() {
      AgentId other = AgentId.of("house-13");
      transcripts.remember(HOUSE, UserMessage.of("mine"));
      transcripts.remember(other, UserMessage.of("theirs"));

      transcripts.forget(HOUSE);

      assertThat(transcripts.recall(HOUSE).messages()).isEmpty();
      assertThat(transcripts.recall(other).messages()).hasSize(1);
    }
  }
}
