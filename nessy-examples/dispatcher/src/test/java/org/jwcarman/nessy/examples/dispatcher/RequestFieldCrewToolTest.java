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
package org.jwcarman.nessy.examples.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * {@link RequestFieldCrewTool} pinned without Spring: the wire name the model calls, and its one
 * execute path — always {@code Parked}, never {@code Ready}, since the crew is out in the world
 * (spec §2).
 */
class RequestFieldCrewToolTest {

  private static ToolContext context(String callId) {
    ToolCall call =
        new ToolCall(callId, "request_field_crew", JsonNodeFactory.instance.objectNode());
    return new ToolContext(ConversationId.generate(), call, EventEmitter.noop());
  }

  @Test
  void names_itself_request_field_crew() {
    RequestFieldCrewTool tool = new RequestFieldCrewTool();

    assertThat(tool.name()).isEqualTo("request_field_crew");
  }

  @Test
  void execute_parks_with_a_fresh_token() {
    RequestFieldCrewTool tool = new RequestFieldCrewTool();

    Awaited<ToolResult> awaited =
        tool.execute(new RequestFieldCrewTool.Input("INC-7", "dispatch a crew"), context("c1"));

    assertThat(awaited).isInstanceOf(Awaited.Parked.class);
    ParkToken token = ((Awaited.Parked<ToolResult>) awaited).token();
    assertThat(token.value()).isNotBlank();
  }

  @Test
  void two_executes_mint_two_distinct_tokens() {
    RequestFieldCrewTool tool = new RequestFieldCrewTool();

    Awaited<ToolResult> first =
        tool.execute(new RequestFieldCrewTool.Input("INC-7", "dispatch a crew"), context("c1"));
    Awaited<ToolResult> second =
        tool.execute(new RequestFieldCrewTool.Input("INC-8", "dispatch a crew"), context("c2"));

    ParkToken firstToken = ((Awaited.Parked<ToolResult>) first).token();
    ParkToken secondToken = ((Awaited.Parked<ToolResult>) second).token();
    assertThat(firstToken).isNotEqualTo(secondToken);
  }
}
