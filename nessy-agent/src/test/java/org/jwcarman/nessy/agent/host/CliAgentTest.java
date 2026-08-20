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
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.LatchedModelProvider;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.model.ModelEvent;

class CliAgentTest {

  @Test
  void helloWorldEndToEnd() throws Exception {
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.TextChunk("Hello "), new ModelEvent.TextChunk("back!"))));
    try (var agent = Nessy.cli().provider(provider).settings(TestSettings.settings()).build()) {
      assertThat(agent.converse("hello")).isEqualTo("Hello back!");
    }
  }

  @Test
  void twoTurnsShareOneMemory() throws Exception {
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.TextChunk("one")),
                List.of(new ModelEvent.TextChunk("two"))));
    try (var agent = Nessy.cli().provider(provider).settings(TestSettings.settings()).build()) {
      agent.converse("first");
      agent.converse("second");
      // the second request's context carries the whole first exchange plus the new user turn
      assertThat(provider.requests()).hasSize(2);
      assertThat(provider.requests().get(1).context().messages()).hasSize(3);
    }
  }

  @Test
  void aBusySecondTurnIsRefusedAndItsLateReplyIsNeverMisattributed() throws Exception {
    var gate = new CountDownLatch(1);
    var provider =
        new LatchedModelProvider(
            gate,
            List.of(
                List.of(new ModelEvent.TextChunk("late answer")),
                List.of(new ModelEvent.TextChunk("fresh answer"))));
    try (var agent = Nessy.cli().provider(provider).settings(TestSettings.settings()).build()) {
      assertThatThrownBy(() -> agent.converse("first", Duration.ofMillis(100)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("timed out");
      assertThatThrownBy(() -> agent.converse("second"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("still in flight");
      gate.countDown();
      awaitLastTurnDone(agent);
      assertThat(agent.converse("third")).isEqualTo("fresh answer");
    }
  }

  private static void awaitLastTurnDone(CliAgent agent) throws InterruptedException {
    var deadline = System.currentTimeMillis() + 5_000;
    while (!agent.lastTurnDone() && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
  }

  @Test
  void aCallerSuppliedExecutorSurvivesAgentClose() throws Exception {
    ExecutorService callerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    var provider = new ScriptedModelProvider(List.of(List.of(new ModelEvent.TextChunk("hi"))));
    try (var agent =
        Nessy.cli()
            .provider(provider)
            .settings(TestSettings.settings())
            .executor(callerExecutor)
            .build()) {
      agent.converse("hello");
    }
    assertThat(callerExecutor.isShutdown()).isFalse();
    callerExecutor.close();
  }

  @Test
  void twoBuildsNeverShareTheDefaultMemory() throws Exception {
    var firstProvider =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.TextChunk("one"))));
    try (var first =
        Nessy.cli().provider(firstProvider).settings(TestSettings.settings()).build()) {
      first.converse("hello");
    }
    var secondProvider =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.TextChunk("two"))));
    try (var second =
        Nessy.cli().provider(secondProvider).settings(TestSettings.settings()).build()) {
      second.converse("hi");
    }
    // a fresh build starts with an empty memory: just the one user turn, not the first agent's
    assertThat(secondProvider.requests()).hasSize(1);
    assertThat(secondProvider.requests().getFirst().context().messages()).hasSize(1);
  }

  @Test
  void aToolCallingTurnRunsTheWholeLoop() throws Exception {
    var call = new ToolCall("c1", "echo", JsonNodeFactory.instance.objectNode().put("value", "hi"));
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(call, null)),
                List.of(new ModelEvent.TextChunk("echoed: hi"))));
    try (var agent =
        Nessy.cli()
            .provider(provider)
            .settings(TestSettings.settings())
            .tools(new EchoTool())
            .build()) {
      assertThat(agent.converse("run the tool")).isEqualTo("echoed: hi");
    }
    assertThat(provider.requests()).hasSize(2);
    // the second request's context must carry the tool_use turn and its tool_result answer
    assertThat(provider.requests().get(1).context().messages()).hasSize(3);
  }

  record EchoInput(String value) {}

  static final class EchoTool implements Tool<EchoInput> {
    @Override
    public String name() {
      return "echo";
    }

    @Override
    public String description() {
      return "echoes";
    }

    @Override
    public Class<EchoInput> inputType() {
      return EchoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("echo: " + input.value()));
    }
  }
}
