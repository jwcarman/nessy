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
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.Role;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.SessionStatus;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ToolCall;
import org.jwcarman.nessy.api.ToolResult;
import org.jwcarman.nessy.api.ToolResultBlock;
import org.jwcarman.nessy.api.ToolUseBlock;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.api.event.ToolProgress;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.spi.context.ContextBuilder;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.session.InMemoryTranscriptStore;
import org.jwcarman.nessy.spi.session.SessionStore;
import org.jwcarman.nessy.spi.session.TranscriptEntry;
import org.jwcarman.nessy.spi.session.TranscriptStore;

class InProcessEngineTest {

  private static final SessionId ID = new SessionId("s1");
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
    public boolean requiresApproval() {
      return false;
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
    public boolean requiresApproval() {
      return false;
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
    public boolean requiresApproval() {
      return false;
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
      ModelProvider provider, ToolRegistry tools, Approver approver, SessionStore store) {
    return engineWith(provider, tools, approver, store, EventHub.synchronous());
  }

  private static InProcessEngine engineWith(
      ModelProvider provider,
      ToolRegistry tools,
      Approver approver,
      SessionStore store,
      EventHub hub) {
    return engineWith(provider, tools, approver, store, hub, TranscriptStore.none());
  }

  private static InProcessEngine engineWith(
      ModelProvider provider,
      ToolRegistry tools,
      Approver approver,
      SessionStore store,
      EventHub hub,
      TranscriptStore transcript) {
    return new InProcessEngine(
        provider,
        tools,
        approver,
        store,
        hub,
        Reducer.defaults(),
        CONFIG,
        new ObjectMapper(),
        ObservationRegistry.NOOP,
        ContextBuilder.identity(),
        transcript);
  }

  @Nested
  class Construction {

    @Test
    void a_null_provider_is_rejected() {
      assertThatThrownBy(
              () ->
                  new InProcessEngine(
                      null,
                      ToolRegistry.of(),
                      Approver.allowAll(),
                      SessionStore.inMemory(),
                      EventHub.synchronous(),
                      Reducer.defaults(),
                      CONFIG,
                      new ObjectMapper(),
                      ObservationRegistry.NOOP,
                      ContextBuilder.identity(),
                      TranscriptStore.none()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("provider");
    }

    @Test
    void a_null_context_builder_is_rejected() {
      assertThatThrownBy(
              () ->
                  new InProcessEngine(
                      new EngineFixtures.FakeProvider(List.of()),
                      ToolRegistry.of(),
                      Approver.allowAll(),
                      SessionStore.inMemory(),
                      EventHub.synchronous(),
                      Reducer.defaults(),
                      CONFIG,
                      new ObjectMapper(),
                      ObservationRegistry.NOOP,
                      null,
                      TranscriptStore.none()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("contextBuilder");
    }

    @Test
    void a_null_transcript_store_is_rejected() {
      assertThatThrownBy(
              () ->
                  new InProcessEngine(
                      new EngineFixtures.FakeProvider(List.of()),
                      ToolRegistry.of(),
                      Approver.allowAll(),
                      SessionStore.inMemory(),
                      EventHub.synchronous(),
                      Reducer.defaults(),
                      CONFIG,
                      new ObjectMapper(),
                      ObservationRegistry.NOOP,
                      ContextBuilder.identity(),
                      null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("transcript");
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
          engineWith(provider, ToolRegistry.of(), Approver.allowAll(), SessionStore.inMemory())
              .run(ID, Event.UserSaid.of("what is 2+2?"));

      assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().status()).isEqualTo(SessionStatus.COMPLETE);
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
                  SessionStore.inMemory())
              .run(ID, Event.UserSaid.of("echo hi"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().messages()).hasSize(4);
      assertThat(completed.state().messages().get(2).role()).isEqualTo(Role.USER);
      assertThat(completed.state().messages().get(2).content())
          .containsExactly(new ToolResultBlock("c1", "echoed:hi", false));
      assertThat(completed.state().status()).isEqualTo(SessionStatus.COMPLETE);
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
                  SessionStore.inMemory())
              .run(ID, Event.UserSaid.of("echo a and b"));

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
              SessionStore.inMemory())
          .run(ID, Event.UserSaid.of("echo hi"));

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
                  SessionStore.inMemory())
              .run(ID, Event.UserSaid.of("echo hi"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().messages().get(2).content())
          .containsExactly(new ToolResultBlock("c1", "Denied by user: not allowed", true));
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
          engineWith(provider, ToolRegistry.of(), Approver.allowAll(), SessionStore.inMemory())
              .run(ID, Event.UserSaid.of("go"));

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
                  SessionStore.inMemory())
              .run(ID, Event.UserSaid.of("go"));

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
                  SessionStore.inMemory())
              .run(ID, Event.UserSaid.of("go"));

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
      SessionStore store = SessionStore.inMemory();

      assertThatThrownBy(
              () ->
                  engineWith(
                          provider,
                          ToolRegistry.of(new EngineFixtures.EchoTool(true)),
                          new ThrowingApprover(),
                          store)
                      .run(ID, Event.UserSaid.of("echo hi")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("approver blew up");

      SessionState saved = store.load(ID).orElseThrow();
      assertThat(saved.messages()).contains(Message.user("echo hi"));
    }

    @Test
    void progress_is_saved_even_when_the_run_blows_up() {
      // The hub contains subscriber exceptions (see EventHubTest), so a throwing
      // observer can no longer be the source of a blown-up run. The failure has
      // to come from the provider itself instead.
      SessionStore store = SessionStore.inMemory();

      assertThatThrownBy(
              () ->
                  engineWith(
                          new ExplodingStreamProvider(),
                          ToolRegistry.of(),
                          Approver.allowAll(),
                          store)
                      .run(ID, Event.UserSaid.of("what is 2+2?")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("stream blew up");

      SessionState saved = store.load(ID).orElseThrow();
      assertThat(saved.messages()).containsExactly(Message.user("what is 2+2?"));
      assertThat(saved.pendingBlocks()).containsExactly(new TextBlock("Four."));
    }

    @Test
    void resume_is_refused_because_this_engine_never_parks() {
      EngineFixtures.FakeProvider provider = new EngineFixtures.FakeProvider(List.of());

      assertThatThrownBy(
              () ->
                  engineWith(
                          provider, ToolRegistry.of(), Approver.allowAll(), SessionStore.inMemory())
                      .resume(ID, ParkToken.generate(), Event.UserSaid.of("x")))
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

      assertThatThrownBy(
              () ->
                  engineWith(
                          provider,
                          ToolRegistry.of(new ParkingTool()),
                          Approver.allowAll(),
                          SessionStore.inMemory())
                      .run(ID, Event.UserSaid.of("go")))
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
      SessionStore store = SessionStore.inMemory();

      RunOutcome outcome =
          engineWith(
                  provider,
                  ToolRegistry.of(new EngineFixtures.EchoTool(true)),
                  Approver.allowAll(),
                  store)
              .run(ID, Event.UserSaid.of("echo hi"));

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
      SessionStore store = SessionStore.inMemory();

      engineWith(provider, ToolRegistry.of(), Approver.allowAll(), store)
          .run(ID, Event.UserSaid.of("what is 2+2?"));

      assertThat(store.load(ID)).isPresent();
      assertThat(store.load(ID).orElseThrow().status()).isEqualTo(SessionStatus.COMPLETE);
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
              SessionStore.inMemory())
          .run(ID, Event.UserSaid.of("echo hi"));

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
      SessionStore store = SessionStore.inMemory();
      InProcessEngine engine = engineWith(provider, ToolRegistry.of(), Approver.allowAll(), store);

      engine.run(ID, Event.UserSaid.of("what is 2+2?"));
      RunOutcome outcome = engine.run(ID, Event.UserSaid.of("what is 2+3?"));

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
      EventHub hub = EventHub.synchronous();
      List<Event> events = new ArrayList<>();
      hub.subscribe(SessionEvent.class, sessionEvent -> events.add(sessionEvent.event()));

      engineWith(provider, ToolRegistry.of(), Approver.allowAll(), SessionStore.inMemory(), hub)
          .run(ID, Event.UserSaid.of("what is 2+2?"));

      assertThat(events)
          .containsExactly(
              Event.UserSaid.of("what is 2+2?"),
              new Event.TextDelta("Fo"),
              new Event.TextDelta("ur."),
              new Event.ModelTurnEnded(StopReason.END_TURN, Usage.zero()));
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
      EventHub hub = EventHub.synchronous();
      List<ToolProgress> progress = new ArrayList<>();
      hub.subscribe(ToolProgress.class, progress::add);

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
            public boolean requiresApproval() {
              return false;
            }

            @Override
            public Awaited<ToolResult> execute(EngineFixtures.Echo input, ToolContext context) {
              context.events().emit(new ToolProgress(context.sessionId(), "c1", "halfway"));
              return Awaited.ready(ToolResult.ok("done"));
            }
          };

      engineWith(
              provider, ToolRegistry.of(noisy), Approver.allowAll(), SessionStore.inMemory(), hub)
          .run(ID, Event.UserSaid.of("go"));

      assertThat(progress).containsExactly(new ToolProgress(ID, "c1", "halfway"));
    }
  }

  @Nested
  class Transcript {

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
      InMemoryTranscriptStore transcript = TranscriptStore.inMemory();

      engineWith(
              provider,
              ToolRegistry.of(new EngineFixtures.EchoTool(true)),
              Approver.allowAll(),
              SessionStore.inMemory(),
              EventHub.synchronous(),
              transcript)
          .run(ID, Event.UserSaid.of("echo hi"));

      List<TranscriptEntry> entries = transcript.entries(ID);
      assertThat(entries).hasSize(4);
      assertThat(entries.get(0).message()).isEqualTo(Message.user("echo hi"));
      assertThat(entries.get(0).turnUsage()).isEqualTo(Usage.zero());
      assertThat(entries.get(1).message().role()).isEqualTo(Role.ASSISTANT);
      assertThat(entries.get(1).message().content())
          .containsExactly(
              new ToolUseBlock(new ToolCall("c1", "echo", EngineFixtures.echoArgs("hi"))));
      assertThat(entries.get(1).turnUsage()).isEqualTo(toolTurnUsage);
      assertThat(entries.get(2).message().role()).isEqualTo(Role.USER);
      assertThat(entries.get(2).message().content())
          .containsExactly(new ToolResultBlock("c1", "echoed:hi", false));
      assertThat(entries.get(2).turnUsage()).isEqualTo(Usage.zero());
      assertThat(entries.get(3).message())
          .isEqualTo(Message.assistant(List.of(new TextBlock("Done."))));
      assertThat(entries.get(3).turnUsage()).isEqualTo(finalTurnUsage);
    }

    @Test
    void a_failing_journal_fails_the_run_loudly() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("Four."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
      SessionStore store = SessionStore.inMemory();
      TranscriptStore explodingTranscript =
          (id, entry) -> {
            throw new IllegalStateException("journal blew up");
          };

      assertThatThrownBy(
              () ->
                  engineWith(
                          provider,
                          ToolRegistry.of(),
                          Approver.allowAll(),
                          store,
                          EventHub.synchronous(),
                          explodingTranscript)
                      .run(ID, Event.UserSaid.of("what is 2+2?")))
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
        public void close() {}
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }
}
