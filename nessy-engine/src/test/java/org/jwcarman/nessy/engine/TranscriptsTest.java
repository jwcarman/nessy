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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.block.AssistantContentBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.message.AssistantMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ToolResultMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

@DisplayName("What an agent remembers")
class TranscriptsTest {

  private static final AgentId HOUSE = AgentId.of("house-12");
  private Transcripts transcripts;

  @BeforeEach
  void setUp() {
    transcripts =
        new Transcripts(new InMemorySubstrate(Clock.systemUTC()), AgentType.of("watchman"));
  }

  private static AssistantMessage calling(String callId) {
    return new AssistantMessage(
        List.of(
            (AssistantContentBlock)
                new ToolCallBlock(
                    new ToolCall(callId, "read_file", JsonNodeFactory.instance.objectNode()))));
  }

  private static ToolResultMessage answering(String callId) {
    return new ToolResultMessage(List.of(ToolResultBlock.of(callId, ToolResult.ok("done"))));
  }

  @Test
  void a_fresh_agent_remembers_nothing() {
    assertThat(transcripts.recall(HOUSE).messages()).isEmpty();
  }

  @Test
  @DisplayName("messages come back as themselves, in order")
  void messages_round_trip_polymorphically() {
    transcripts.remember(HOUSE, UserMessage.of("hi"));
    transcripts.remember(HOUSE, new AssistantMessage(List.of(new TextBlock("hello"))));

    Context context = transcripts.recall(HOUSE);

    assertThat(context.messages()).hasSize(2);
    assertThat(context.messages().get(0)).isInstanceOf(UserMessage.class);
    assertThat(context.messages().get(1)).isInstanceOf(AssistantMessage.class);
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
    transcripts.remember(HOUSE, calling("c1"), answering("c1"));

    Context context = transcripts.recall(HOUSE);

    assertThat(context.messages()).hasSize(3);
    assertThat(context.messages().get(2)).isInstanceOf(ToolResultMessage.class);
  }

  @Test
  void an_assistant_turn_with_unanswered_calls_is_refused_on_its_own() {
    AssistantMessage asking = calling("c1");

    assertThatThrownBy(() -> transcripts.remember(HOUSE, asking))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be remembered with the message answering it");
  }

  @Test
  void results_that_do_not_match_the_calls_are_refused() {
    AssistantMessage asking = calling("c1");
    ToolResultMessage wrong = answering("c2");

    assertThatThrownBy(() -> transcripts.remember(HOUSE, asking, wrong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("answer exactly the calls asked");
  }
}
