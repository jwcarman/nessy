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
package org.jwcarman.nessy.engine.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.tool.ToolCalls.ResolvedCall;
import tools.jackson.databind.json.JsonMapper;

/** A call found in the event that recorded it, by the key of its entry and its position. */
@DisplayName("Requested calls")
class RequestedCallsTest {

  private static final CallId SAME_ID = new CallId("call_1");
  private static final ToolName TOOL = new ToolName("lookup");
  private static final IdempotencyKey FIRST_KEY = IdempotencyKey.of(UUID.randomUUID());
  private static final IdempotencyKey SECOND_KEY = IdempotencyKey.of(UUID.randomUUID());

  private final Payloads payloads =
      new InMemoryPayloads(new JacksonCodecFactory(JsonMapper.builder().build()));

  private AgentEvent.ActionsRequested asked(
      List<Block.ActionRequestContent> content, List<ActionRequest> entries) {
    PayloadRef ref = payloads.put(content);
    return new AgentEvent.ActionsRequested(
        new Seq(2), new TurnId(1), ref, entries, Usage.unreported(), Optional.empty());
  }

  private static ActionRequest.ToolCall entry(IdempotencyKey key, String action) {
    return new ActionRequest.ToolCall(SAME_ID, TOOL, action, key);
  }

  private static Block.ToolCall block(String arguments) {
    return new Block.ToolCall(SAME_ID, TOOL, arguments);
  }

  private AgentEvent.ActionsRequested twoCallsWithOneCallId() {
    return asked(
        List.of(block("{\"q\":\"one\"}"), block("{\"q\":\"two\"}")),
        List.of(entry(FIRST_KEY, "look up one"), entry(SECOND_KEY, "look up two")));
  }

  private Optional<ResolvedCall> resolve(AgentEvent.ActionsRequested asked, IdempotencyKey key) {
    return RequestedCalls.resolve(payloads, asked, e -> e.idempotencyKey().equals(key));
  }

  @Test
  void the_second_of_two_calls_with_one_call_id_gets_its_own_action_and_arguments() {
    Optional<ResolvedCall> found = resolve(twoCallsWithOneCallId(), SECOND_KEY);

    assertThat(found).isPresent();
    assertThat(found.get().action()).isEqualTo("look up two");
    assertThat(found.get().call().arguments()).isEqualTo("{\"q\":\"two\"}");
  }

  @Test
  void the_first_of_two_calls_with_one_call_id_gets_its_own_action_and_arguments() {
    Optional<ResolvedCall> found = resolve(twoCallsWithOneCallId(), FIRST_KEY);

    assertThat(found).isPresent();
    assertThat(found.get().action()).isEqualTo("look up one");
    assertThat(found.get().call().arguments()).isEqualTo("{\"q\":\"one\"}");
  }

  @Test
  void finding_by_call_id_gives_the_first_entry_and_its_own_block() {
    Optional<ResolvedCall> found =
        RequestedCalls.resolve(payloads, twoCallsWithOneCallId(), e -> e.id().equals(SAME_ID));

    assertThat(found).isPresent();
    assertThat(found.get().action()).isEqualTo("look up one");
    assertThat(found.get().call().arguments()).isEqualTo("{\"q\":\"one\"}");
  }

  @Test
  void nothing_is_guessed_when_there_is_no_block_at_the_entrys_position() {
    AgentEvent.ActionsRequested mismatched =
        asked(
            List.of(block("{\"q\":\"one\"}")),
            List.of(entry(FIRST_KEY, "look up one"), entry(SECOND_KEY, "look up two")));

    assertThat(resolve(mismatched, SECOND_KEY)).isEmpty();
  }

  @Test
  void nothing_is_guessed_when_the_block_at_the_position_is_another_call() {
    AgentEvent.ActionsRequested otherCall =
        asked(
            List.of(
                block("{\"q\":\"one\"}"),
                new Block.ToolCall(new CallId("call_9"), TOOL, "{\"q\":\"two\"}")),
            List.of(entry(FIRST_KEY, "look up one"), entry(SECOND_KEY, "look up two")));

    assertThat(resolve(otherCall, SECOND_KEY)).isEmpty();
  }

  @Test
  void nothing_is_guessed_when_the_block_at_the_position_names_another_tool() {
    AgentEvent.ActionsRequested otherTool =
        asked(
            List.of(
                block("{\"q\":\"one\"}"),
                new Block.ToolCall(SAME_ID, new ToolName("ping"), "{\"q\":\"two\"}")),
            List.of(entry(FIRST_KEY, "look up one"), entry(SECOND_KEY, "look up two")));

    assertThat(resolve(otherTool, SECOND_KEY)).isEmpty();
  }

  @Test
  void a_block_with_no_entry_before_the_entrys_own_block_leaves_the_entry_unresolved() {
    AgentEvent.ActionsRequested unaligned =
        asked(
            List.of(
                new Block.ToolCall(new CallId("call_0"), TOOL, "{\"q\":\"zero\"}"),
                block("{\"q\":\"one\"}")),
            List.of(entry(FIRST_KEY, "look up one")));

    assertThat(resolve(unaligned, FIRST_KEY)).isEmpty();
  }
}
