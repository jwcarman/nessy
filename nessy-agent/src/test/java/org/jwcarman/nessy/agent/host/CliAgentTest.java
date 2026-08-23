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
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.support.LatchedModel;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.spi.model.ModelEvent;

class CliAgentTest {

  @Test
  void helloWorldEndToEnd() {
    var provider =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.TextChunk("Hello "), new ModelEvent.TextChunk("back!"))));
    try (var agent =
        Nessy.cli()
            .model(provider)
            .systemPrompt(TestSettings.SYSTEM_PROMPT)
            .settings(TestSettings.settings())
            .build()) {
      assertThat(agent.converse("hello")).isEqualTo("Hello back!");
    }
  }

  /**
   * Fix round 1, item 1: {@code Nessy.cli()}'s build now runs its {@link Harness} through the same
   * compiler every door shares, so it starts a delivery heartbeat exactly like any other harness's
   * — {@link CliAgent#close()} must quiesce it, or the ephemeral-CLI charter (one turn, then gone)
   * is violated by a stranded daemon thread. Enumerated by name prefix rather than a new public
   * seam: {@code DeliveryWorker}'s heartbeat thread is always named {@code "nessy-delivery"}.
   */
  @Test
  void closingACliAgentLeavesNoLiveDeliveryHeartbeatThread() throws InterruptedException {
    var provider = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hello back"))));
    long before = liveDeliveryThreadCount();

    var agent =
        Nessy.cli()
            .model(provider)
            .systemPrompt(TestSettings.SYSTEM_PROMPT)
            .settings(TestSettings.settings())
            .build();
    assertThat(agent.converse("hello")).isEqualTo("hello back");
    assertThat(liveDeliveryThreadCount()).isEqualTo(before + 1);

    agent.close();

    // Thread#interrupt() is asynchronous — give the heartbeat a moment to actually stop.
    long deadline = System.currentTimeMillis() + 2000;
    long after = liveDeliveryThreadCount();
    while (after > before && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
      after = liveDeliveryThreadCount();
    }
    assertThat(after).isEqualTo(before);
  }

  private static long liveDeliveryThreadCount() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(Thread::isAlive)
        .filter(t -> t.getName().equals("nessy-delivery"))
        .count();
  }

  @Test
  void twoTurnsShareOneMemory() {
    var provider =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.TextChunk("one")),
                List.of(new ModelEvent.TextChunk("two"))));
    try (var agent =
        Nessy.cli()
            .model(provider)
            .systemPrompt(TestSettings.SYSTEM_PROMPT)
            .settings(TestSettings.settings())
            .build()) {
      agent.converse("first");
      agent.converse("second");
      // the second request's context carries the whole first exchange plus the new user turn
      assertThat(provider.requests()).hasSize(2);
      assertThat(provider.requests().get(1).context().messages()).hasSize(3);
    }
  }

  @Test
  void aBusySecondTurnIsRefusedAndItsLateReplyIsNeverMisattributed() {
    var gate = new CountDownLatch(1);
    var provider =
        new LatchedModel(
            gate,
            List.of(
                List.of(new ModelEvent.TextChunk("late answer")),
                List.of(new ModelEvent.TextChunk("fresh answer"))));
    try (var agent =
        Nessy.cli()
            .model(provider)
            .systemPrompt(TestSettings.SYSTEM_PROMPT)
            .settings(TestSettings.settings())
            .build()) {
      var shortTimeout = Duration.ofMillis(100);
      assertThatThrownBy(() -> agent.converse("first", shortTimeout))
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

  private static void awaitLastTurnDone(CliAgent agent) {
    agent.current().await(Duration.ofSeconds(5));
  }

  @Test
  void aCallerSuppliedExecutorSurvivesAgentClose() {
    ExecutorService callerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    var provider = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hi"))));
    try (var agent =
        Nessy.cli()
            .model(provider)
            .systemPrompt(TestSettings.SYSTEM_PROMPT)
            .settings(TestSettings.settings())
            .executor(callerExecutor)
            .build()) {
      agent.converse("hello");
    }
    assertThat(callerExecutor.isShutdown()).isFalse();
    callerExecutor.close();
  }

  @Test
  void twoBuildsNeverShareTheDefaultMemory() {
    var firstProvider = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("one"))));
    try (var first =
        Nessy.cli()
            .model(firstProvider)
            .systemPrompt(TestSettings.SYSTEM_PROMPT)
            .settings(TestSettings.settings())
            .build()) {
      first.converse("hello");
    }
    var secondProvider = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("two"))));
    try (var second =
        Nessy.cli()
            .model(secondProvider)
            .systemPrompt(TestSettings.SYSTEM_PROMPT)
            .settings(TestSettings.settings())
            .build()) {
      second.converse("hi");
    }
    // a fresh build starts with an empty memory: just the one user turn, not the first agent's
    assertThat(secondProvider.requests()).hasSize(1);
    assertThat(secondProvider.requests().getFirst().context().messages()).hasSize(1);
  }

  @Test
  void aToolCallingTurnRunsTheWholeLoop() {
    var call = new ToolCall("c1", "echo", JsonNodeFactory.instance.objectNode().put("value", "hi"));
    var provider =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(call, null)),
                List.of(new ModelEvent.TextChunk("echoed: hi"))));
    try (var agent =
        Nessy.cli()
            .model(provider)
            .systemPrompt(TestSettings.SYSTEM_PROMPT)
            .settings(TestSettings.settings())
            .tools(new EchoTool())
            .build()) {
      assertThat(agent.converse("run the tool")).isEqualTo("echoed: hi");
    }
    assertThat(provider.requests()).hasSize(2);
    // the second request's context must carry the tool_use turn and its tool_result answer
    assertThat(provider.requests().get(1).context().messages()).hasSize(3);
  }

  @Test
  void aDurableOnlyToolsSpecIsAbsentFromWhatTheCliDoorShowsTheModel() {
    var provider = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hi"))));
    try (var agent =
        Nessy.cli()
            .model(provider)
            .systemPrompt(TestSettings.SYSTEM_PROMPT)
            .settings(TestSettings.settings())
            .tools(new EchoTool(), new DurableOnlyTool())
            .build()) {
      agent.converse("hello");
    }

    List<ToolSpec> specs = provider.requests().getFirst().tools();

    assertThat(specs)
        .isNotEmpty()
        .noneMatch(spec -> spec.name().equals("durable_only"))
        .anyMatch(spec -> spec.name().equals("echo"));
  }

  record EchoInput(String value) {}

  static final class DurableOnlyTool implements Tool<EchoInput> {
    @Override
    public String name() {
      return "durable_only";
    }

    @Override
    public String description() {
      return "only ever answers through a durable computation";
    }

    @Override
    public Class<EchoInput> inputType() {
      return EchoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
      return Awaited.deferred();
    }

    @Override
    public CompletionPolicy requiredCompletion() {
      return CompletionPolicy.DURABLE;
    }
  }

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
