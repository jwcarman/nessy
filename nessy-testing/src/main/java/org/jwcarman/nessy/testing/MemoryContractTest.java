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
package org.jwcarman.nessy.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The runnable conformance harness every {@link Memory} implementation owes: recall respects
 * remember order, agents cannot read each other's transcripts, an assistant turn that called tools
 * is refused unless its answers come with it, and a recalled context is always one a provider will
 * accept. A third-party {@code Memory} extends this class directly and implements {@link
 * #freshMemory()} — the one abstract member — to run every test here against its own instance.
 *
 * <p>Public and main-scope, on purpose: a conformance suite that only test-scoped code could see
 * would be unusable by a {@code Memory} implementation living outside this repository. Pulls in
 * {@code junit-jupiter-api} and {@code assertj-core} directly, never the {@code junit-jupiter}
 * aggregator, so depending on this class never drags a test engine onto a caller's own main
 * classpath (this module's {@code pom.xml} already notes the convention).
 */
public abstract class MemoryContractTest {

  private static final AgentId ONE = AgentId.of("agent-one");
  private static final AgentId TWO = AgentId.of("agent-two");

  /** A brand-new, empty {@link Memory} instance — a fresh one per call, never reused or shared. */
  protected abstract Memory freshMemory();

  @Test
  void recallingAnAgentThatHasNeverBeenToldAnythingIsEmpty() {
    assertThat(freshMemory().recall(ONE).messages()).isEmpty();
  }

  @Test
  void recallReturnsMessagesInRememberOrder() {
    Memory memory = freshMemory();

    memory.remember(ONE, UserMessage.of("first"));
    memory.remember(ONE, UserMessage.of("second"));
    memory.remember(ONE, UserMessage.of("third"));

    assertThat(memory.recall(ONE).messages())
        .containsExactly(
            UserMessage.of("first"), UserMessage.of("second"), UserMessage.of("third"));
  }

  @Test
  void oneAgentCannotReadAnother() {
    Memory memory = freshMemory();

    memory.remember(ONE, UserMessage.of("mine"));

    assertThat(memory.recall(TWO).messages()).isEmpty();
  }

  /**
   * An exchange goes in and comes back whole — as one message, because that is what it is.
   *
   * <p>Two tests used to live beside this one, policing that a calling turn could not be remembered
   * alone and that answers had to match their calls. Neither can be written any more: {@code
   * ExchangeMessage} refuses to exist in those states, so a store has nothing left to get wrong and
   * the rule is tested where it is enforced.
   */
  @Test
  void anExchangeIsRememberedWhole() {
    Memory memory = freshMemory();
    ToolCall call = new ToolCall(CallId.of("c1"), "lookup", JsonNodeFactory.instance.objectNode());
    ExchangeMessage exchange =
        new ExchangeMessage(
            List.of(new ToolCallBlock(call)),
            List.of(ToolResultBlock.of(CallId.of("c1"), ToolResult.ok("42"))));

    memory.remember(ONE, exchange);

    List<ContextMessage> messages = memory.recall(ONE).messages();
    assertThat(messages).containsExactly(exchange);
  }
}
