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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.event.ListenerRegistration;
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.api.event.MessageAppended;
import org.jwcarman.nessy.api.event.ToolProgress;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.context.ContextPipeline;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;

class InProcessEngineTest {

  private static final ConversationId ID = new ConversationId("s1");
  private static final ModelSettings CONFIG =
      new ModelSettings("fake-model", "be helpful", 1024, Set.of(), null);

  /** A tool that throws, to prove the loop survives a broken tool. */
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
    public Awaited<ToolResult> execute(EngineFixtures.Echo input, ToolContext context) {
      throw new IllegalStateException("kaboom");
    }
  }

  /** A tool that always parks, to prove InProcessEngine refuses rather than swallows it. */
  private static final class ParkingTool implements Tool<EngineFixtures.Echo> {

    @Override
    public String name() {
      return "park";
    }

    @Override
    public String description() {
      return "Always parks";
    }

    @Override
    public Class<EngineFixtures.Echo> inputType() {
      return EngineFixtures.Echo.class;
    }

    @Override
    public Awaited<ToolResult> execute(EngineFixtures.Echo input, ToolContext context) {
      return Awaited.parked(ParkToken.generate());
    }
  }

  /** A tool that throws with no message, to prove the errored result names the exception type. */
  private static final class MessagelessExplodingTool implements Tool<EngineFixtures.Echo> {

    @Override
    public String name() {
      return "boom";
    }

    @Override
    public String description() {
      return "Always throws without a message";
    }

    @Override
    public Class<EngineFixtures.Echo> inputType() {
      return EngineFixtures.Echo.class;
    }

    @Override
    public Awaited<ToolResult> execute(EngineFixtures.Echo input, ToolContext context) {
      throw new RuntimeException();
    }
  }

  /** An approver that always throws, to prove the exception is not swallowed by the engine. */
  private static final class ThrowingApprover implements Approver {

    @Override
    public Awaited<Decision> approve(ApprovalRequest request) {
      throw new IllegalStateException("approver blew up");
    }
  }

  /** An approver that records whether it was ever consulted. */
  private static final class CountingApprover implements Approver {

    private int calls;
    private final Approver delegate;

    CountingApprover(Approver delegate) {
      this.delegate = delegate;
    }

    @Override
    public Awaited<Decision> approve(ApprovalRequest request) {
      calls++;
      return delegate.approve(request);
    }
  }

  /** Arguments that do not bind to {@link EngineFixtures.Echo}: a typo'd field name. */
  private static ObjectNode malformedArgs(String value) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.put("vlaue", value);
    return args;
  }

  private static InProcessEngine engineWith(
      ModelProvider provider, ToolRegistry tools, Approver approver, ConversationStore store) {
    return engineWith(provider, tools, approver, store, EventEmitter.noop());
  }

  private static InProcessEngine engineWith(
      ModelProvider provider,
      ToolRegistry tools,
      Approver approver,
      ConversationStore store,
      EventEmitter events) {
    return new InProcessEngine(
        provider,
        tools,
        EngineFixtures.defaultGrants(tools),
        approver,
        store,
        events,
        Reducer.defaults(),
        CONFIG,
        new ObjectMapper(),
        ObservationRegistry.NOOP,
        ContextPipeline.builder().build(events, ObservationRegistry.NOOP));
  }

  @Nested
  class Construction {

    @Test
    void a_null_provider_is_rejected() {
      ToolRegistry tools = ToolRegistry.of();
      Approver approver = Approver.allowAll();
      ConversationStore store = ConversationStore.inMemory();
      EventEmitter events = EventEmitter.noop();
      Reducer reducer = Reducer.defaults();
      ObjectMapper mapper = new ObjectMapper();
      ContextPipeline contextPipeline = EngineFixtures.contextPipeline();

      assertThatThrownBy(
              () ->
                  new InProcessEngine(
                      null,
                      tools,
                      Map.of(),
                      approver,
                      store,
                      events,
                      reducer,
                      CONFIG,
                      mapper,
                      ObservationRegistry.NOOP,
                      contextPipeline))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("provider");
    }

    @Test
    void a_null_grants_map_is_rejected() {
      EngineFixtures.FakeProvider provider = new EngineFixtures.FakeProvider(List.of());
      ToolRegistry tools = ToolRegistry.of();
      Approver approver = Approver.allowAll();
      ConversationStore store = ConversationStore.inMemory();
      EventEmitter events = EventEmitter.noop();
      Reducer reducer = Reducer.defaults();
      ObjectMapper mapper = new ObjectMapper();
      ContextPipeline contextPipeline = EngineFixtures.contextPipeline();

      assertThatThrownBy(
              () ->
                  new InProcessEngine(
                      provider,
                      tools,
                      null,
                      approver,
                      store,
                      events,
                      reducer,
                      CONFIG,
                      mapper,
                      ObservationRegistry.NOOP,
                      contextPipeline))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("grants");
    }

    @Test
    void a_grant_map_missing_a_registered_tool_is_rejected() {
      ToolRegistry tools = ToolRegistry.of(new EngineFixtures.EchoTool(false));
      EngineFixtures.FakeProvider provider = new EngineFixtures.FakeProvider(List.of());
      Approver approver = Approver.allowAll();
      ConversationStore store = ConversationStore.inMemory();
      EventEmitter events = EventEmitter.noop();
      Reducer reducer = Reducer.defaults();
      ObjectMapper mapper = new ObjectMapper();
      ContextPipeline contextPipeline = EngineFixtures.contextPipeline();

      assertThatThrownBy(
              () ->
                  new InProcessEngine(
                      provider,
                      tools,
                      Map.of(),
                      approver,
                      store,
                      events,
                      reducer,
                      CONFIG,
                      mapper,
                      ObservationRegistry.NOOP,
                      contextPipeline))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("echo");
    }

    @Test
    void a_null_context_pipeline_is_rejected() {
      EngineFixtures.FakeProvider provider = new EngineFixtures.FakeProvider(List.of());
      ToolRegistry tools = ToolRegistry.of();
      Approver approver = Approver.allowAll();
      ConversationStore store = ConversationStore.inMemory();
      EventEmitter events = EventEmitter.noop();
      Reducer reducer = Reducer.defaults();
      ObjectMapper mapper = new ObjectMapper();

      assertThatThrownBy(
              () ->
                  new InProcessEngine(
                      provider,
                      tools,
                      Map.of(),
                      approver,
                      store,
                      events,
                      reducer,
                      CONFIG,
                      mapper,
                      ObservationRegistry.NOOP,
                      null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("contextPipeline");
    }
  }

  @Nested
  class Plain_answers {

    @Test
    void a_plain_answer_completes_the_session() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("Four."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));

      RunOutcome outcome =
          engineWith(provider, ToolRegistry.of(), Approver.allowAll(), ConversationStore.inMemory())
              .run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"));

      assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(completed.state().messages())
          .containsExactly(
              Message.user("what is 2+2?"), Message.assistant(List.of(new TextBlock("Four."))));
    }
  }

  @Nested
  class Tool_calls {

    @Test
    void a_tool_call_runs_and_feeds_its_result_back() {
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

      RunOutcome outcome =
          engineWith(
                  provider,
                  ToolRegistry.of(new EngineFixtures.EchoTool(true)),
                  Approver.allowAll(),
                  ConversationStore.inMemory())
              .run(ID, ConversationEvent.AgentTold.of(ID, "echo hi"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().messages()).hasSize(4);
      assertThat(completed.state().messages().get(2).role()).isEqualTo(Role.USER);
      assertThat(completed.state().messages().get(2).content())
          .containsExactly(new ToolResultBlock("c1", "echoed:hi", false));
      assertThat(completed.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    @Test
    void two_tool_calls_in_one_turn_batch_into_one_result_message() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(
                          new ToolCall("c1", "echo", EngineFixtures.echoArgs("a"))),
                      new ModelEvent.ToolUseEmitted(
                          new ToolCall("c2", "echo", EngineFixtures.echoArgs("b"))),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero())),
                  List.of(
                      new ModelEvent.TextChunk("Done."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));

      RunOutcome outcome =
          engineWith(
                  provider,
                  ToolRegistry.of(new EngineFixtures.EchoTool(false)),
                  Approver.allowAll(),
                  ConversationStore.inMemory())
              .run(ID, ConversationEvent.AgentTold.of(ID, "echo a and b"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().messages()).hasSize(4);
      assertThat(completed.state().messages().get(2).content())
          .containsExactly(
              new ToolResultBlock("c1", "echoed:a", false),
              new ToolResultBlock("c2", "echoed:b", false));
    }
  }

  @Nested
  class Approval {

    @Test
    void tools_that_do_not_require_approval_never_reach_the_approver() {
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
      CountingApprover approver = new CountingApprover(Approver.allowAll());

      engineWith(
              provider,
              ToolRegistry.of(new EngineFixtures.EchoTool(false)),
              approver,
              ConversationStore.inMemory())
          .run(ID, ConversationEvent.AgentTold.of(ID, "echo hi"));

      assertThat(approver.calls).isZero();
    }

    @Test
    void a_denial_becomes_an_errored_result_rather_than_an_exception() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(
                          new ToolCall("c1", "echo", EngineFixtures.echoArgs("hi"))),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero())),
                  List.of(
                      new ModelEvent.TextChunk("Understood."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));

      RunOutcome outcome =
          engineWith(
                  provider,
                  ToolRegistry.of(new EngineFixtures.EchoTool(true)),
                  Approver.denyAll("not allowed"),
                  ConversationStore.inMemory())
              .run(ID, ConversationEvent.AgentTold.of(ID, "echo hi"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().messages().get(2).content())
          .containsExactly(new ToolResultBlock("c1", "Denied by user: not allowed", true));
    }
  }

  /**
   * The authority chokepoint: {@code decide()} consults only a grant's {@link UsagePolicy} — a tool
   * carries no authority of its own. These prove the three {@link PolicyDecision} outcomes route
   * correctly and that a broken policy fails closed.
   */
  @Nested
  class Authority {

    /** A policy that always throws, to prove a broken policy denies rather than allows. */
    private static final class ThrowingPolicy implements UsagePolicy {
      @Override
      public PolicyDecision evaluate(ToolCall call, ConversationState state) {
        throw new IllegalStateException("policy blew up");
      }
    }

    private static InProcessEngine engineWithGrant(
        ModelProvider provider, ToolGrant grant, Approver approver) {
      return new InProcessEngine(
          provider,
          ToolRegistry.of(grant.tool()),
          Map.of(grant.tool().name(), grant),
          approver,
          ConversationStore.inMemory(),
          EventEmitter.noop(),
          Reducer.defaults(),
          CONFIG,
          new ObjectMapper(),
          ObservationRegistry.NOOP,
          EngineFixtures.contextPipeline());
    }

    private static EngineFixtures.FakeProvider toolCallingProvider() {
      return new EngineFixtures.FakeProvider(
          List.of(
              List.of(
                  new ModelEvent.ToolUseEmitted(
                      new ToolCall("c1", "echo", EngineFixtures.echoArgs("hi"))),
                  new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero())),
              List.of(
                  new ModelEvent.TextChunk("Done."),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
    }

    @Test
    void an_allow_grant_skips_the_approver() {
      Tool<EngineFixtures.Echo> tool = new EngineFixtures.EchoTool(true);
      ToolGrant grant = new ToolGrant(tool, UsagePolicy.allow());

      RunOutcome outcome =
          engineWithGrant(toolCallingProvider(), grant, new ThrowingApprover())
              .run(ID, ConversationEvent.AgentTold.of(ID, "echo hi"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().messages().get(2).content())
          .containsExactly(new ToolResultBlock("c1", "echoed:hi", false));
    }

    @Test
    void a_deny_grant_answers_the_model_without_the_approver() {
      Tool<EngineFixtures.Echo> tool = new EngineFixtures.EchoTool(false);
      ToolGrant grant = new ToolGrant(tool, UsagePolicy.deny("not for you"));

      RunOutcome outcome =
          engineWithGrant(toolCallingProvider(), grant, new ThrowingApprover())
              .run(ID, ConversationEvent.AgentTold.of(ID, "echo hi"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().messages().get(2).content())
          .containsExactly(new ToolResultBlock("c1", "Denied by user: not for you", true));
    }

    @Test
    void a_require_approval_grant_asks_the_approver() {
      Tool<EngineFixtures.Echo> tool = new EngineFixtures.EchoTool(false);
      ToolGrant grant = new ToolGrant(tool, UsagePolicy.requireApproval());
      CountingApprover approver = new CountingApprover(Approver.allowAll());

      RunOutcome outcome =
          engineWithGrant(toolCallingProvider(), grant, approver)
              .run(ID, ConversationEvent.AgentTold.of(ID, "echo hi"));

      assertThat(approver.calls).isEqualTo(1);
      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().messages().get(2).content())
          .containsExactly(new ToolResultBlock("c1", "echoed:hi", false));
    }

    @Test
    void a_throwing_policy_fails_closed() {
      Tool<EngineFixtures.Echo> tool = new EngineFixtures.EchoTool(false);
      ToolGrant grant = new ToolGrant(tool, new ThrowingPolicy());

      RunOutcome outcome =
          engineWithGrant(toolCallingProvider(), grant, new ThrowingApprover())
              .run(ID, ConversationEvent.AgentTold.of(ID, "echo hi"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      ToolResultBlock block =
          (ToolResultBlock) completed.state().messages().get(2).content().getFirst();
      assertThat(block.isError()).isTrue();
      assertThat(block.content()).contains("policy blew up");
    }

    @Test
    void a_policy_returning_null_fails_closed() {
      Tool<EngineFixtures.Echo> tool = new EngineFixtures.EchoTool(false);
      ToolGrant grant = new ToolGrant(tool, (call, state) -> null);

      RunOutcome outcome =
          engineWithGrant(toolCallingProvider(), grant, new ThrowingApprover())
              .run(ID, ConversationEvent.AgentTold.of(ID, "echo hi"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      ToolResultBlock block =
          (ToolResultBlock) completed.state().messages().get(2).content().getFirst();
      assertThat(block.isError()).isTrue();
      assertThat(block.content()).contains("policy returned no decision");
    }

    @Test
    void a_call_to_a_truly_unregistered_tool_is_allowed_through_to_the_no_such_tool_error() {
      EngineFixtures.FakeProvider provider = toolCallingProvider();
      InProcessEngine engine =
          new InProcessEngine(
              provider,
              ToolRegistry.of(),
              Map.of(),
              new ThrowingApprover(),
              ConversationStore.inMemory(),
              EventEmitter.noop(),
              Reducer.defaults(),
              CONFIG,
              new ObjectMapper(),
              ObservationRegistry.NOOP,
              EngineFixtures.contextPipeline());

      RunOutcome outcome = engine.run(ID, ConversationEvent.AgentTold.of(ID, "echo hi"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      ToolResultBlock block =
          (ToolResultBlock) completed.state().messages().get(2).content().getFirst();
      assertThat(block.isError()).isTrue();
      assertThat(block.content()).contains("echo");
    }

    /**
     * A registry that resolves a tool via {@link #find} without ever advertising it in {@link
     * #specs}. {@code AgentBuilder} never builds one of these — it always derives its grants from
     * the same registry's {@code specs()} — but the constructor belt only walks {@code specs()}, so
     * a registry shaped like this is the one way to reach {@code decide()} with a tool that is
     * registered-but-ungranted, exercising the guard the belt cannot.
     */
    private static final class UndeclaredToolRegistry implements ToolRegistry {
      private final Tool<?> hidden;

      UndeclaredToolRegistry(Tool<?> hidden) {
        this.hidden = hidden;
      }

      @Override
      public Optional<Tool<?>> find(String name) {
        return hidden.name().equals(name) ? Optional.of(hidden) : Optional.empty();
      }

      @Override
      public List<ToolSpec> specs() {
        return List.of();
      }
    }

    @Test
    void a_registered_but_ungranted_tool_is_denied_at_the_chokepoint() {
      EngineFixtures.FakeProvider provider = toolCallingProvider();
      ToolRegistry undeclared = new UndeclaredToolRegistry(new EngineFixtures.EchoTool(false));
      InProcessEngine engine =
          new InProcessEngine(
              provider,
              undeclared,
              Map.of(),
              new ThrowingApprover(),
              ConversationStore.inMemory(),
              EventEmitter.noop(),
              Reducer.defaults(),
              CONFIG,
              new ObjectMapper(),
              ObservationRegistry.NOOP,
              EngineFixtures.contextPipeline());

      RunOutcome outcome = engine.run(ID, ConversationEvent.AgentTold.of(ID, "echo hi"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      ToolResultBlock block =
          (ToolResultBlock) completed.state().messages().get(2).content().getFirst();
      assertThat(block.isError()).isTrue();
      assertThat(block.content()).isEqualTo("Denied by user: no grant for tool: echo");
    }
  }

  @Nested
  class Failure_handling {

    @Test
    void an_unknown_tool_becomes_an_errored_result_the_model_can_see() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(
                          new ToolCall("c1", "missing", EngineFixtures.echoArgs("hi"))),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero())),
                  List.of(
                      new ModelEvent.TextChunk("Oh."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));

      RunOutcome outcome =
          engineWith(provider, ToolRegistry.of(), Approver.allowAll(), ConversationStore.inMemory())
              .run(ID, ConversationEvent.AgentTold.of(ID, "go"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      ToolResultBlock block =
          (ToolResultBlock) completed.state().messages().get(2).content().getFirst();
      assertThat(block.isError()).isTrue();
      assertThat(block.content()).contains("missing");
    }

    @Test
    void a_throwing_tool_becomes_an_errored_result_rather_than_killing_the_loop() {
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

      Tool<EngineFixtures.Echo> exploding = new ExplodingTool();

      RunOutcome outcome =
          engineWith(
                  provider,
                  ToolRegistry.of(exploding),
                  Approver.allowAll(),
                  ConversationStore.inMemory())
              .run(ID, ConversationEvent.AgentTold.of(ID, "go"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      ToolResultBlock block =
          (ToolResultBlock) completed.state().messages().get(2).content().getFirst();
      assertThat(block.isError()).isTrue();
      assertThat(block.content()).contains("kaboom");
    }

    @Test
    void a_tool_that_throws_with_no_message_reports_its_class_name_rather_than_null() {
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

      RunOutcome outcome =
          engineWith(
                  provider,
                  ToolRegistry.of(new MessagelessExplodingTool()),
                  Approver.allowAll(),
                  ConversationStore.inMemory())
              .run(ID, ConversationEvent.AgentTold.of(ID, "go"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      ToolResultBlock block =
          (ToolResultBlock) completed.state().messages().get(2).content().getFirst();
      assertThat(block.isError()).isTrue();
      assertThat(block.content()).isEqualTo("RuntimeException");
    }

    @Test
    void a_throwing_approver_propagates_but_still_leaves_progress_persisted() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(
                          new ToolCall("c1", "echo", EngineFixtures.echoArgs("hi"))),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()))));
      ConversationStore store = ConversationStore.inMemory();
      InProcessEngine engine =
          engineWith(
              provider,
              ToolRegistry.of(new EngineFixtures.EchoTool(true)),
              new ThrowingApprover(),
              store);

      assertThatThrownBy(() -> engine.run(ID, ConversationEvent.AgentTold.of(ID, "echo hi")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("approver blew up");

      ConversationState saved = store.load(ID).orElseThrow();
      assertThat(saved.messages()).contains(Message.user("echo hi"));
    }

    @Test
    void progress_is_saved_even_when_the_run_blows_up() {
      // The hub contains subscriber exceptions (see EventHubTest), so a throwing
      // observer can no longer be the source of a blown-up run. The failure has
      // to come from the provider itself instead.
      ConversationStore store = ConversationStore.inMemory();
      InProcessEngine engine =
          engineWith(new ExplodingStreamProvider(), ToolRegistry.of(), Approver.allowAll(), store);

      assertThatThrownBy(() -> engine.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("stream blew up");

      ConversationState saved = store.load(ID).orElseThrow();
      assertThat(saved.messages()).containsExactly(Message.user("what is 2+2?"));
      assertThat(saved.pendingBlocks()).containsExactly(new TextBlock("Four."));
    }

    @Test
    void resume_is_refused_because_this_engine_never_parks() {
      EngineFixtures.FakeProvider provider = new EngineFixtures.FakeProvider(List.of());
      InProcessEngine engine =
          engineWith(
              provider, ToolRegistry.of(), Approver.allowAll(), ConversationStore.inMemory());

      assertThatThrownBy(
              () ->
                  engine.resume(ID, ParkToken.generate(), ConversationEvent.AgentTold.of(ID, "x")))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("DurableEngine");
    }

    @Test
    void a_parking_tool_is_refused_rather_than_swallowed() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(
                          new ToolCall("c1", "park", EngineFixtures.echoArgs("hi"))),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()))));
      InProcessEngine engine =
          engineWith(
              provider,
              ToolRegistry.of(new ParkingTool()),
              Approver.allowAll(),
              ConversationStore.inMemory());

      assertThatThrownBy(() -> engine.run(ID, ConversationEvent.AgentTold.of(ID, "go")))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("DurableEngine");
    }

    @Test
    void malformed_arguments_on_an_approval_requiring_tool_are_recoverable() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(
                          new ToolCall("c1", "echo", malformedArgs("hi"))),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero())),
                  List.of(
                      new ModelEvent.TextChunk("Oh."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
      ConversationStore store = ConversationStore.inMemory();

      RunOutcome outcome =
          engineWith(
                  provider,
                  ToolRegistry.of(new EngineFixtures.EchoTool(true)),
                  Approver.allowAll(),
                  store)
              .run(ID, ConversationEvent.AgentTold.of(ID, "echo hi"));

      assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      ToolResultBlock block =
          (ToolResultBlock) completed.state().messages().get(2).content().getFirst();
      assertThat(block.isError()).isTrue();
      assertThat(store.load(ID)).isPresent();
    }
  }

  @Nested
  class Streams_and_sessions {

    @Test
    void the_final_state_is_saved() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("Four."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
      ConversationStore store = ConversationStore.inMemory();

      engineWith(provider, ToolRegistry.of(), Approver.allowAll(), store)
          .run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"));

      assertThat(store.load(ID)).isPresent();
      assertThat(store.load(ID).orElseThrow().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    @Test
    void the_model_stream_is_closed_after_each_turn() {
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

      engineWith(
              provider,
              ToolRegistry.of(new EngineFixtures.EchoTool(true)),
              Approver.allowAll(),
              ConversationStore.inMemory())
          .run(ID, ConversationEvent.AgentTold.of(ID, "echo hi"));

      assertThat(provider.closedCount).isEqualTo(2);
      assertThat(provider.openStreams).isZero();
      assertThat(provider.maxOpenStreams).isEqualTo(1);
    }

    @Test
    void a_second_run_continues_the_saved_session() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("Four."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero())),
                  List.of(
                      new ModelEvent.TextChunk("Five."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
      ConversationStore store = ConversationStore.inMemory();
      InProcessEngine engine = engineWith(provider, ToolRegistry.of(), Approver.allowAll(), store);

      engine.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"));
      RunOutcome outcome = engine.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+3?"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().messages())
          .containsExactly(
              Message.user("what is 2+2?"),
              Message.assistant(List.of(new TextBlock("Four."))),
              Message.user("what is 2+3?"),
              Message.assistant(List.of(new TextBlock("Five."))));
    }
  }

  @Nested
  class Events_and_progress {

    @Test
    void the_hub_sees_every_event_as_it_happens() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("Fo"),
                      new ModelEvent.TextChunk("ur."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
      List<ConversationEvent> events = new ArrayList<>();
      ListenerRegistry hub =
          ListenerRegistry.of(
              List.of(ListenerRegistration.sync(ConversationEvent.class, events::add)));

      engineWith(
              provider, ToolRegistry.of(), Approver.allowAll(), ConversationStore.inMemory(), hub)
          .run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"));

      assertThat(events)
          .containsExactly(
              ConversationEvent.AgentTold.of(ID, "what is 2+2?"),
              new ConversationEvent.TextDelta(ID, "Fo"),
              new ConversationEvent.TextDelta(ID, "ur."),
              new ConversationEvent.ModelTurnEnded(ID, StopReason.END_TURN, Usage.zero()));
    }

    @Test
    void tools_can_report_progress_through_the_hub() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(
                          new ToolCall("c1", "noisy", EngineFixtures.echoArgs("hi"))),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero())),
                  List.of(
                      new ModelEvent.TextChunk("Done."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
      List<ToolProgress> progress = new ArrayList<>();
      ListenerRegistry hub =
          ListenerRegistry.of(
              List.of(ListenerRegistration.sync(ToolProgress.class, progress::add)));

      Tool<EngineFixtures.Echo> noisy =
          new Tool<>() {
            @Override
            public String name() {
              return "noisy";
            }

            @Override
            public String description() {
              return "Reports progress";
            }

            @Override
            public Class<EngineFixtures.Echo> inputType() {
              return EngineFixtures.Echo.class;
            }

            @Override
            public Awaited<ToolResult> execute(EngineFixtures.Echo input, ToolContext context) {
              context.events().emit(new ToolProgress(context.conversationId(), "c1", "halfway"));
              return Awaited.ready(ToolResult.ok("done"));
            }
          };

      engineWith(
              provider,
              ToolRegistry.of(noisy),
              Approver.allowAll(),
              ConversationStore.inMemory(),
              hub)
          .run(ID, ConversationEvent.AgentTold.of(ID, "go"));

      assertThat(progress).containsExactly(new ToolProgress(ID, "c1", "halfway"));
    }
  }

  @Nested
  class Transcript {

    /**
     * The journal rides no dedicated engine dependency (design §9.1/§17): the engine emits {@link
     * MessageAppended} at the newborn choke point, and a plain recording listener — exactly what a
     * {@code .listen(MessageAppended.class, journal::add)} declaration on the builders wires — sees
     * every message born, in birth order.
     */
    @Test
    void every_message_is_journaled_at_birth() {
      Usage toolTurnUsage = new Usage(10, 5, 0);
      Usage finalTurnUsage = new Usage(20, 8, 0);
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(
                          new ToolCall("c1", "echo", EngineFixtures.echoArgs("hi"))),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, toolTurnUsage)),
                  List.of(
                      new ModelEvent.TextChunk("Done."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, finalTurnUsage))));
      List<MessageAppended> journal = new ArrayList<>();
      ListenerRegistry hub =
          ListenerRegistry.of(
              List.of(ListenerRegistration.sync(MessageAppended.class, journal::add)));

      engineWith(
              provider,
              ToolRegistry.of(new EngineFixtures.EchoTool(true)),
              Approver.allowAll(),
              ConversationStore.inMemory(),
              hub)
          .run(ID, ConversationEvent.AgentTold.of(ID, "echo hi"));

      assertThat(journal).hasSize(4);
      assertThat(journal.get(0).message()).isEqualTo(Message.user("echo hi"));
      assertThat(journal.get(0).turnUsage()).isEqualTo(Usage.zero());
      assertThat(journal.get(1).message().role()).isEqualTo(Role.ASSISTANT);
      assertThat(journal.get(1).message().content())
          .containsExactly(
              new ToolUseBlock(new ToolCall("c1", "echo", EngineFixtures.echoArgs("hi"))));
      assertThat(journal.get(1).turnUsage()).isEqualTo(toolTurnUsage);
      assertThat(journal.get(2).message().role()).isEqualTo(Role.USER);
      assertThat(journal.get(2).message().content())
          .containsExactly(new ToolResultBlock("c1", "echoed:hi", false));
      assertThat(journal.get(2).turnUsage()).isEqualTo(Usage.zero());
      assertThat(journal.get(3).message())
          .isEqualTo(Message.assistant(List.of(new TextBlock("Done."))));
      assertThat(journal.get(3).turnUsage()).isEqualTo(finalTurnUsage);
    }

    /**
     * The strictness proof is exactly the hub's throwing-listener contract (design §9.1/§17): a
     * synchronous {@link MessageAppended} listener that throws propagates straight out of {@code
     * hub.emit}, out of the engine's newborn announcement, and out of {@code run} — the veto is the
     * throw. {@code run}'s {@code finally} still saves whatever progress reached the session store
     * before the throw.
     */
    @Test
    void a_throwing_journaling_listener_fails_the_run_loudly() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("Four."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
      ConversationStore store = ConversationStore.inMemory();
      ListenerRegistry hub =
          ListenerRegistry.of(
              List.of(
                  ListenerRegistration.sync(
                      MessageAppended.class,
                      event -> {
                        throw new IllegalStateException("journal blew up");
                      })));
      InProcessEngine engine =
          engineWith(provider, ToolRegistry.of(), Approver.allowAll(), store, hub);

      assertThatThrownBy(() -> engine.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("journal blew up");

      assertThat(store.load(ID)).isPresent();
    }
  }

  /** A model stream that fails mid-turn, to prove the run still persists what it reached. */
  private static final class ExplodingStreamProvider implements ModelProvider {

    @Override
    public ModelStream stream(ModelRequest request) {
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return new Iterator<>() {
            private int calls;

            @Override
            public boolean hasNext() {
              return true;
            }

            @Override
            public ModelEvent next() {
              calls++;
              if (calls == 1) {
                return new ModelEvent.TextChunk("Four.");
              }
              throw new IllegalStateException("stream blew up");
            }
          };
        }

        @Override
        public void close() {
          // intentionally empty: this fake stream holds no resources to release
        }
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }
}
