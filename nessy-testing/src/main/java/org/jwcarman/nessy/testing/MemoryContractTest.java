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
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;

/**
 * The runnable conformance harness every {@link Memory} implementation owes (remembrance spec §5):
 * idempotent re-remember converges to one fact; {@link Memory#recall()} respects remember order;
 * the {@link Remembrance.ToolExchange} pairing invariant never splits; re-delivery of facts already
 * remembered is tolerated. A third-party {@code Memory} extends this class directly and implements
 * {@link #freshMemory()} — the one abstract member — to run every test here against its own
 * instance, exactly as {@link org.jwcarman.nessy.agent.memory.SubstrateMemory} (nessy-agent) does.
 *
 * <p>Public and main-scope, on purpose (spec §5): a conformance suite that only test-scoped code
 * could see would be unusable by a Memory implementation living outside this repository. Pulls in
 * {@code junit-jupiter-api} and {@code assertj-core} directly, never the {@code junit-jupiter}
 * aggregator, so depending on this class never drags a test engine onto a caller's own main
 * classpath (mirrors the {@code nessy-tck} convention this module's {@code pom.xml} already notes).
 */
public abstract class MemoryContractTest {

  /** A brand-new, empty {@link Memory} instance — a fresh one per call, never reused or shared. */
  protected abstract Memory freshMemory();

  @Test
  void rememberingTheSameKeyTwiceConvergesToOneFact() {
    Memory memory = freshMemory();
    Remembrance remembrance = new Remembrance.UserMessage("turn-1", Message.user("hello"));

    memory.remember(remembrance);
    memory.remember(remembrance);

    assertThat(memory.recall().messages()).containsExactly(Message.user("hello"));
  }

  @Test
  void recallReturnsMessagesInRememberOrder() {
    Memory memory = freshMemory();

    memory.remember(new Remembrance.UserMessage("turn-1", Message.user("first")));
    memory.remember(new Remembrance.UserMessage("turn-2", Message.user("second")));
    memory.remember(new Remembrance.UserMessage("turn-3", Message.user("third")));

    assertThat(memory.recall().messages())
        .containsExactly(Message.user("first"), Message.user("second"), Message.user("third"));
  }

  @Test
  void theToolExchangePairingInvariantNeverSplits() {
    Memory memory = freshMemory();
    ToolCall call = new ToolCall("c1", "lookup", JsonNodeFactory.instance.objectNode());
    Message assistantTurn = Message.assistant(List.of(new ToolUseBlock(call)));

    memory.remember(new Remembrance.AssistantMessage("response-1", assistantTurn));
    memory.remember(new Remembrance.ToolExchange("exec-1", call, ToolResult.ok("42")));

    // Context's own validating constructor already enforces the pairing invariant — an
    // unanswered tool_use, or a stray tool_result, throws building the Context recall() returns.
    // A successful recall() here IS the proof that the exchange paired, whole, with its call.
    List<Message> messages = memory.recall().messages();
    assertThat(messages).hasSize(2);
    assertThat(messages.getFirst()).isEqualTo(assistantTurn);
    assertThat(messages.getLast().content())
        .containsExactly(new ToolResultBlock("c1", "42", false));
  }

  @Test
  void theToolExchangePairingInvariantSurvivesArrivingBeforeItsAssistantMessage() {
    // The exact ordering the durable worker produces for a tool call that finishes before its
    // sibling calls do (remembrance spec §2): the exchange is remembered first, the assistant
    // message only once every sibling call has answered.
    Memory memory = freshMemory();
    ToolCall call = new ToolCall("c1", "lookup", JsonNodeFactory.instance.objectNode());
    Message assistantTurn = Message.assistant(List.of(new ToolUseBlock(call)));

    memory.remember(new Remembrance.ToolExchange("exec-1", call, ToolResult.ok("42")));
    memory.remember(new Remembrance.AssistantMessage("response-1", assistantTurn));

    List<Message> messages = memory.recall().messages();
    assertThat(messages).hasSize(2);
    assertThat(messages.getFirst()).isEqualTo(assistantTurn);
    assertThat(messages.getLast().content())
        .containsExactly(new ToolResultBlock("c1", "42", false));
  }

  @Test
  void reDeliveryOfAlreadyRememberedFactsIsTolerated() {
    // The at-least-once redrive story (remembrance spec §1 law 1): a caller that crashes between
    // its own remember and its own commit redrives the whole turn, re-remembering every fact the
    // fold implied — including ones it already remembered before the crash.
    Memory memory = freshMemory();
    ToolCall call = new ToolCall("c1", "lookup", JsonNodeFactory.instance.objectNode());
    Message assistantTurn = Message.assistant(List.of(new ToolUseBlock(call)));
    Remembrance userFact = new Remembrance.UserMessage("turn-1", Message.user("hi"));
    Remembrance assistantFact = new Remembrance.AssistantMessage("response-1", assistantTurn);
    Remembrance exchangeFact = new Remembrance.ToolExchange("exec-1", call, ToolResult.ok("42"));

    memory.remember(userFact);
    memory.remember(assistantFact);
    memory.remember(exchangeFact);
    // the redrive: the exact same facts, in the exact same order, remembered again
    memory.remember(userFact);
    memory.remember(assistantFact);
    memory.remember(exchangeFact);

    List<Message> messages = memory.recall().messages();
    assertThat(messages).hasSize(3);
    assertThat(messages.getFirst()).isEqualTo(Message.user("hi"));
    assertThat(messages.get(1)).isEqualTo(assistantTurn);
    assertThat(messages.getLast().content())
        .containsExactly(new ToolResultBlock("c1", "42", false));
  }

  @Test
  void aFreshMemoryRecallsAnEmptyContext() {
    assertThat(freshMemory().recall().messages()).isEmpty();
  }
}
