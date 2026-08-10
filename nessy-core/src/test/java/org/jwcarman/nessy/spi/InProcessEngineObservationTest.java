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
package org.jwcarman.nessy.spi;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.context.ContextPipeline;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelSettings;

class InProcessEngineObservationTest {

  private static final ConversationId ID = new ConversationId("s1");
  private static final ModelSettings CONFIG =
      new ModelSettings("fake-model", "be helpful", 1024, Set.of(), null);

  @Test
  void a_tool_calling_run_produces_the_span_taxonomy() {
    TestObservationRegistry observations = TestObservationRegistry.create();
    runToolCallingConversation(observations);

    assertThat(observations)
        .hasObservationWithNameEqualTo("nessy.run")
        .that()
        .hasContextualNameEqualTo("invoke_agent")
        .hasHighCardinalityKeyValueWithKey("gen_ai.conversation.id");
    assertThat(observations)
        .hasObservationWithNameEqualTo("nessy.model.call")
        .that()
        .hasContextualNameEqualTo("chat fake-model")
        .hasLowCardinalityKeyValue("gen_ai.request.model", "fake-model");
    assertThat(observations).hasObservationWithNameEqualTo("nessy.turn");
    assertThat(observations)
        .hasObservationWithNameEqualTo("nessy.tool.call")
        .that()
        .hasContextualNameEqualTo("execute_tool echo")
        .hasLowCardinalityKeyValue("gen_ai.tool.name", "echo");
  }

  @Test
  void an_approval_gated_tool_produces_an_approval_wait_span() {
    TestObservationRegistry observations = TestObservationRegistry.create();
    runToolCallingConversation(observations, /* requiresApproval= */ true);

    assertThat(observations).hasObservationWithNameEqualTo("nessy.approval.wait");
  }

  @Test
  void a_throwing_approver_records_an_error_on_the_approval_wait_span() {
    TestObservationRegistry observations = TestObservationRegistry.create();
    EngineFixtures.FakeProvider provider =
        new EngineFixtures.FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(
                        new ToolCall("c1", "echo", EngineFixtures.echoArgs("hi"))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()))));

    ToolRegistry tools = ToolRegistry.of(new EngineFixtures.EchoTool(true));
    InProcessEngine engine =
        new InProcessEngine(
            provider,
            tools,
            EngineFixtures.defaultGrants(tools),
            new ThrowingApprover(),
            ConversationStore.inMemory(),
            EventHub.synchronous(),
            Reducer.defaults(),
            CONFIG,
            new ObjectMapper(),
            observations,
            ContextPipeline.builder().build(EventHub.synchronous(), observations));

    assertThatThrownBy(() -> engine.run(ID, ConversationEvent.UserSaid.of(ID, "echo hi")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(observations).hasObservationWithNameEqualTo("nessy.approval.wait").that().hasError();
  }

  /** An approver that always throws, to prove the failure reaches the approval-wait span. */
  private static final class ThrowingApprover implements Approver {

    @Override
    public Awaited<Decision> approve(ApprovalRequest request) {
      throw new IllegalStateException("approver blew up");
    }
  }

  @Test
  void a_succeeding_tool_call_carries_a_success_outcome() {
    TestObservationRegistry observations = TestObservationRegistry.create();
    runToolCallingConversation(observations);

    assertThat(observations)
        .hasObservationWithNameEqualTo("nessy.tool.call")
        .that()
        .hasLowCardinalityKeyValue("nessy.tool.outcome", "success");
  }

  @Test
  void a_throwing_tool_produces_an_errored_span_with_an_error_outcome() {
    TestObservationRegistry observations = TestObservationRegistry.create();
    EngineFixtures.FakeProvider provider =
        new EngineFixtures.FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(
                        new ToolCall("c1", "boom", EngineFixtures.echoArgs("hi"))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero())),
                List.of(
                    new ModelEvent.TextChunk("Oh."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));

    ToolRegistry tools = ToolRegistry.of(new ExplodingTool());
    InProcessEngine engine =
        new InProcessEngine(
            provider,
            tools,
            EngineFixtures.defaultGrants(tools),
            Approver.allowAll(),
            ConversationStore.inMemory(),
            EventHub.synchronous(),
            Reducer.defaults(),
            CONFIG,
            new ObjectMapper(),
            observations,
            ContextPipeline.builder().build(EventHub.synchronous(), observations));

    engine.run(ID, ConversationEvent.UserSaid.of(ID, "go"));

    assertThat(observations)
        .hasObservationWithNameEqualTo("nessy.tool.call")
        .that()
        .hasError()
        .hasLowCardinalityKeyValue("nessy.tool.outcome", "error");
  }

  /** A tool that throws, to prove a tool failure is visible on its span. */
  private static final class ExplodingTool implements Tool<EngineFixtures.Echo> {

    @Override
    public String name() {
      return "boom";
    }

    @Override
    public String description() {
      return "Always throws";
    }

    @Override
    public Class<EngineFixtures.Echo> inputType() {
      return EngineFixtures.Echo.class;
    }

    @Override
    public boolean requiresApproval() {
      return false;
    }

    @Override
    public Awaited<ToolResult> execute(EngineFixtures.Echo input, ToolContext context) {
      throw new IllegalStateException("kaboom");
    }
  }

  private static void runToolCallingConversation(TestObservationRegistry observations) {
    runToolCallingConversation(observations, false);
  }

  /**
   * Same two-turn scripted conversation as {@link
   * InProcessEngineTest#aToolCallRunsAndFeedsItsResultBack}: a tool_use turn followed by an
   * END_TURN, built from the shared {@link EngineFixtures}.
   */
  private static void runToolCallingConversation(
      TestObservationRegistry observations, boolean requiresApproval) {
    EngineFixtures.FakeProvider provider =
        new EngineFixtures.FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(
                        new ToolCall("c1", "echo", EngineFixtures.echoArgs("hi"))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero())),
                List.of(
                    new ModelEvent.TextChunk("Done."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));

    ToolRegistry tools = ToolRegistry.of(new EngineFixtures.EchoTool(requiresApproval));
    InProcessEngine engine =
        new InProcessEngine(
            provider,
            tools,
            EngineFixtures.defaultGrants(tools),
            Approver.allowAll(),
            ConversationStore.inMemory(),
            EventHub.synchronous(),
            Reducer.defaults(),
            CONFIG,
            new ObjectMapper(),
            observations,
            ContextPipeline.builder().build(EventHub.synchronous(), observations));

    engine.run(ID, ConversationEvent.UserSaid.of(ID, "echo hi"));
  }
}
