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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
}
