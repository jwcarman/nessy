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
package org.jwcarman.nessy.agent.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolEventListener;
import org.jwcarman.nessy.api.tool.ToolResult;

class IntentToolTest {

  private static ToolContext freshContext() {
    var call =
        new ToolCall(
            "c0", "declare-intent", JsonNodeFactory.instance.objectNode().put("intent", "x"));
    return new ToolContext(call, ToolEventListener.noop(), new CallAddress("ops", "prod-eu", "c0"));
  }

  @Test
  void it_is_named_declare_intent() {
    var tool = new IntentTool(new InMemoryIntentStore());

    assertThat(tool.name()).isEqualTo("declare-intent");
  }

  @Test
  void its_description_tells_the_model_to_declare_before_acting() {
    var tool = new IntentTool(new InMemoryIntentStore());

    assertThat(tool.description())
        .isEqualTo("Declare what you are about to do and why, before using any other tool.");
  }

  @Test
  void it_requires_only_immediate_completion() {
    var tool = new IntentTool(new InMemoryIntentStore());

    assertThat(tool.requiredCompletion()).isEqualTo(CompletionPolicy.IMMEDIATE);
  }

  @Test
  void executing_records_the_declaration_into_the_store() {
    var store = new InMemoryIntentStore();
    var tool = new IntentTool(store);

    tool.execute(
        new IntentTool.DeclareIntent("restart prod-eu to clear the stuck deploy"), freshContext());

    assertThat(store.latest()).contains(new Intent("restart prod-eu to clear the stuck deploy"));
  }

  @Test
  void executing_returns_an_immediately_ready_ok_result() {
    var tool = new IntentTool(new InMemoryIntentStore());

    Awaited<ToolResult> outcome =
        tool.execute(new IntentTool.DeclareIntent("restart prod-eu"), freshContext());

    assertThat(outcome).isEqualTo(Awaited.ready(ToolResult.ok("intent recorded")));
  }
}
