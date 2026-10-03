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

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
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
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.engine.tool.ToolCalls.ResolvedCall;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EventStreamToolCallsTest {

  /** Any key: the tests here are not about which one a call gets. */
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final ToolName LOOKUP = new ToolName("lookup");

  private final JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
  private final InMemoryAgentEvents events = new InMemoryAgentEvents(codecs, Clock.systemUTC());
  private final InMemoryPayloads payloads = new InMemoryPayloads(codecs);
  private final EventStreamToolCalls toolCalls = new EventStreamToolCalls(events, payloads, TYPE);
  private Seq last = Seq.NONE;

  private void append(AgentEvent... appended) {
    events.append(TYPE, AGENT, List.of(appended), last);
    last = appended[appended.length - 1].seq();
  }

  private static Block.ToolCall call(String id, String arguments) {
    return new Block.ToolCall(new CallId(id), LOOKUP, arguments);
  }

  private static ActionRequest.ToolCall stored(String id, String action) {
    return new ActionRequest.ToolCall(new CallId(id), LOOKUP, action, KEY);
  }

  /** A turn that opens at {@code id}, and the request the model made in it at the next seq. */
  private void turnAsking(long id, List<Block.ToolCall> asked, List<ActionRequest> recorded) {
    PayloadRef input = payloads.put(List.of(new Block.Text("q" + id)));
    PayloadRef request = payloads.put(asked);
    append(
        new AgentEvent.TurnStarted(new Seq(id), new TurnId(id), input, Instant.now()),
        new AgentEvent.ActionsRequested(
            new Seq(id + 1), new TurnId(id), request, recorded, Usage.unreported()));
  }

  @Test
  void each_of_two_calls_in_one_request_gets_its_own_sentence() {
    turnAsking(
        1,
        List.of(call("call_1", "{\"q\":\"loch\"}"), call("call_2", "{\"q\":\"ness\"}")),
        List.of(stored("call_1", "look up loch"), stored("call_2", "look up ness")));

    ResolvedCall first = toolCalls.find(AGENT, new Seq(2), new CallId("call_1")).orElseThrow();
    ResolvedCall second = toolCalls.find(AGENT, new Seq(2), new CallId("call_2")).orElseThrow();

    assertThat(first.call().arguments()).isEqualTo("{\"q\":\"loch\"}");
    assertThat(first.action()).isEqualTo("look up loch");
    assertThat(second.call().arguments()).isEqualTo("{\"q\":\"ness\"}");
    assertThat(second.action()).isEqualTo("look up ness");
  }

  @Test
  void the_same_call_id_in_two_turns_is_told_apart_by_the_request_it_came_in() {
    turnAsking(1, List.of(call("call_1", "{\"q\":\"one\"}")), List.of(stored("call_1", "first")));
    turnAsking(3, List.of(call("call_1", "{\"q\":\"two\"}")), List.of(stored("call_1", "second")));

    ResolvedCall inFirst = toolCalls.find(AGENT, new Seq(2), new CallId("call_1")).orElseThrow();
    ResolvedCall inSecond = toolCalls.find(AGENT, new Seq(4), new CallId("call_1")).orElseThrow();

    assertThat(inFirst.turn()).isEqualTo(new TurnId(1));
    assertThat(inFirst.call().arguments()).isEqualTo("{\"q\":\"one\"}");
    assertThat(inFirst.action()).isEqualTo("first");
    assertThat(inSecond.turn()).isEqualTo(new TurnId(3));
    assertThat(inSecond.call().arguments()).isEqualTo("{\"q\":\"two\"}");
    assertThat(inSecond.action()).isEqualTo("second");
  }

  @Test
  void a_call_in_the_request_with_no_action_recorded_for_it_is_not_found() {
    turnAsking(
        1,
        List.of(call("call_1", "{}"), call("call_2", "{}")),
        List.of(stored("call_1", "look up loch")));

    assertThat(toolCalls.find(AGENT, new Seq(2), new CallId("call_2"))).isEmpty();
    assertThat(toolCalls.find(AGENT, new Seq(2), new CallId("call_1"))).isPresent();
  }
}
