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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
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
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.event.AgentEventListener;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.session.SessionStore;

class InProcessEngineTest {

  private static final SessionId ID = new SessionId("s1");
  private static final ModelSettings CONFIG =
      new ModelSettings("fake-model", "be helpful", 1024, Set.of());

  /** A model that replays scripted turns, one per call, and tracks how its streams are held. */
  private static final class FakeProvider implements ModelProvider {

    private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();
    private int closedCount;
    private int openStreams;
    private int maxOpenStreams;

    // Takes a List of turns rather than varargs: generic varargs would raise an
    // unchecked warning, and this project forbids @SuppressWarnings outright.
    FakeProvider(List<List<ModelEvent>> scripted) {
      turns.addAll(scripted);
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      Iterator<ModelEvent> events = turns.removeFirst().iterator();
      openStreams++;
      maxOpenStreams = Math.max(maxOpenStreams, openStreams);
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          openStreams--;
          closedCount++;
        }
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  record Echo(String value) {}

  private static final class EchoTool implements Tool<Echo> {

    private final boolean needsApproval;

    EchoTool(boolean needsApproval) {
      this.needsApproval = needsApproval;
    }

    @Override
    public String name() {
      return "echo";
    }

    @Override
    public String description() {
      return "Echoes its input";
    }

    @Override
    public Class<Echo> inputType() {
      return Echo.class;
    }

    @Override
    public boolean requiresApproval() {
      return needsApproval;
    }

    @Override
    public Awaited<ToolResult> execute(Echo input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("echoed:" + input.value()));
    }
  }

  /** A tool that throws, to prove the loop survives a broken tool. */
  private static final class ExplodingTool implements Tool<Echo> {

    @Override
    public String name() {
      return "boom";
    }

    @Override
    public String description() {
      return "Always throws";
    }

    @Override
    public Class<Echo> inputType() {
      return Echo.class;
    }

    @Override
    public boolean requiresApproval() {
      return false;
    }

    @Override
    public Awaited<ToolResult> execute(Echo input, ToolContext context) {
      throw new IllegalStateException("kaboom");
    }
  }

  /** A tool that always parks, to prove InProcessEngine refuses rather than swallows it. */
  private static final class ParkingTool implements Tool<Echo> {

    @Override
    public String name() {
      return "park";
    }

    @Override
    public String description() {
      return "Always parks";
    }

    @Override
    public Class<Echo> inputType() {
      return Echo.class;
    }

    @Override
    public boolean requiresApproval() {
      return false;
    }

    @Override
    public Awaited<ToolResult> execute(Echo input, ToolContext context) {
      return Awaited.parked(ParkToken.random());
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

  private static ObjectNode echoArgs(String value) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.put("value", value);
    return args;
  }

  /** Arguments that do not bind to {@link Echo}: a typo'd field name. */
  private static ObjectNode malformedArgs(String value) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.put("vlaue", value);
    return args;
  }

  private static InProcessEngine engine(
      ModelProvider provider,
      ToolRegistry tools,
      Approver approver,
      SessionStore store,
      AgentEventListener... listeners) {
    return new InProcessEngine(
        provider,
        tools,
        approver,
        store,
        List.of(listeners),
        Reducer.withDefaults(),
        CONFIG,
        new ObjectMapper());
  }

  @Test
  void aPlainAnswerCompletesTheSession() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.TextChunk("Four."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN))));

    RunOutcome outcome =
        engine(provider, ToolRegistry.of(), Approver.allowAll(), SessionStore.inMemory())
            .run(ID, new Event.UserSaid("what is 2+2?"));

    assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
    RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
    assertThat(completed.state().status()).isEqualTo(SessionStatus.COMPLETE);
    assertThat(completed.state().messages())
        .containsExactly(
            Message.user("what is 2+2?"), Message.assistant(List.of(new TextBlock("Four."))));
  }

  @Test
  void aToolCallRunsAndFeedsItsResultBack() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(new ToolCall("c1", "echo", echoArgs("hi"))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
                List.of(
                    new ModelEvent.TextChunk("Done."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN))));

    RunOutcome outcome =
        engine(
                provider,
                ToolRegistry.of(new EchoTool(true)),
                Approver.allowAll(),
                SessionStore.inMemory())
            .run(ID, new Event.UserSaid("echo hi"));

    RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
    assertThat(completed.state().messages()).hasSize(4);
    assertThat(completed.state().messages().get(2).role()).isEqualTo(Role.USER);
    assertThat(completed.state().messages().get(2).content())
        .containsExactly(new ToolResultBlock("c1", "echoed:hi", false));
    assertThat(completed.state().status()).isEqualTo(SessionStatus.COMPLETE);
  }

  @Test
  void toolsThatDoNotRequireApprovalNeverReachTheApprover() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(new ToolCall("c1", "echo", echoArgs("hi"))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
                List.of(
                    new ModelEvent.TextChunk("Done."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN))));
    CountingApprover approver = new CountingApprover(Approver.allowAll());

    engine(provider, ToolRegistry.of(new EchoTool(false)), approver, SessionStore.inMemory())
        .run(ID, new Event.UserSaid("echo hi"));

    assertThat(approver.calls).isZero();
  }

  @Test
  void aDenialBecomesAnErroredResultRatherThanAnException() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(new ToolCall("c1", "echo", echoArgs("hi"))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
                List.of(
                    new ModelEvent.TextChunk("Understood."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN))));

    RunOutcome outcome =
        engine(
                provider,
                ToolRegistry.of(new EchoTool(true)),
                Approver.denyAll("not allowed"),
                SessionStore.inMemory())
            .run(ID, new Event.UserSaid("echo hi"));

    RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
    assertThat(completed.state().messages().get(2).content())
        .containsExactly(new ToolResultBlock("c1", "Denied by user: not allowed", true));
  }

  @Test
  void anUnknownToolBecomesAnErroredResultTheModelCanSee() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(new ToolCall("c1", "missing", echoArgs("hi"))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
                List.of(
                    new ModelEvent.TextChunk("Oh."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN))));

    RunOutcome outcome =
        engine(provider, ToolRegistry.of(), Approver.allowAll(), SessionStore.inMemory())
            .run(ID, new Event.UserSaid("go"));

    RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
    ToolResultBlock block =
        (ToolResultBlock) completed.state().messages().get(2).content().getFirst();
    assertThat(block.isError()).isTrue();
    assertThat(block.content()).contains("missing");
  }

  @Test
  void aThrowingToolBecomesAnErroredResultRatherThanKillingTheLoop() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(new ToolCall("c1", "boom", echoArgs("hi"))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
                List.of(
                    new ModelEvent.TextChunk("Oh."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN))));

    Tool<Echo> exploding = new ExplodingTool();

    RunOutcome outcome =
        engine(provider, ToolRegistry.of(exploding), Approver.allowAll(), SessionStore.inMemory())
            .run(ID, new Event.UserSaid("go"));

    RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
    ToolResultBlock block =
        (ToolResultBlock) completed.state().messages().get(2).content().getFirst();
    assertThat(block.isError()).isTrue();
    assertThat(block.content()).contains("kaboom");
  }

  @Test
  void listenersSeeEveryEventAsItHappens() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.TextChunk("Fo"),
                    new ModelEvent.TextChunk("ur."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN))));
    RecordingListener listener = new RecordingListener();

    engine(provider, ToolRegistry.of(), Approver.allowAll(), SessionStore.inMemory(), listener)
        .run(ID, new Event.UserSaid("what is 2+2?"));

    assertThat(listener.events)
        .containsExactly(
            new Event.UserSaid("what is 2+2?"),
            new Event.TextDelta("Fo"),
            new Event.TextDelta("ur."),
            new Event.ModelTurnEnded(StopReason.END_TURN));
  }

  @Test
  void theFinalStateIsSaved() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.TextChunk("Four."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN))));
    SessionStore store = SessionStore.inMemory();

    engine(provider, ToolRegistry.of(), Approver.allowAll(), store)
        .run(ID, new Event.UserSaid("what is 2+2?"));

    assertThat(store.load(ID)).isPresent();
    assertThat(store.load(ID).orElseThrow().status()).isEqualTo(SessionStatus.COMPLETE);
  }

  @Test
  void progressIsSavedEvenWhenTheRunBlowsUp() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.TextChunk("Four."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN))));
    SessionStore store = SessionStore.inMemory();

    assertThatThrownBy(
            () ->
                engine(
                        provider,
                        ToolRegistry.of(),
                        Approver.allowAll(),
                        store,
                        new ExplodingListener())
                    .run(ID, new Event.UserSaid("what is 2+2?")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("listener blew up");

    SessionState saved = store.load(ID).orElseThrow();
    assertThat(saved.messages()).containsExactly(Message.user("what is 2+2?"));
    assertThat(saved.pendingBlocks()).containsExactly(new TextBlock("Four."));
  }

  @Test
  void resumeIsRefusedBecauseThisEngineNeverParks() {
    FakeProvider provider = new FakeProvider(List.of());

    assertThatThrownBy(
            () ->
                engine(provider, ToolRegistry.of(), Approver.allowAll(), SessionStore.inMemory())
                    .resume(ID, ParkToken.random(), new Event.UserSaid("x")))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("DurableEngine");
  }

  @Test
  void aParkingToolIsRefusedRatherThanSwallowed() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(new ToolCall("c1", "park", echoArgs("hi"))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE))));

    assertThatThrownBy(
            () ->
                engine(
                        provider,
                        ToolRegistry.of(new ParkingTool()),
                        Approver.allowAll(),
                        SessionStore.inMemory())
                    .run(ID, new Event.UserSaid("go")))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("DurableEngine");
  }

  @Test
  void malformedArgumentsOnAnApprovalRequiringToolAreRecoverable() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(new ToolCall("c1", "echo", malformedArgs("hi"))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
                List.of(
                    new ModelEvent.TextChunk("Oh."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN))));
    SessionStore store = SessionStore.inMemory();

    RunOutcome outcome =
        engine(provider, ToolRegistry.of(new EchoTool(true)), Approver.allowAll(), store)
            .run(ID, new Event.UserSaid("echo hi"));

    assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
    RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
    ToolResultBlock block =
        (ToolResultBlock) completed.state().messages().get(2).content().getFirst();
    assertThat(block.isError()).isTrue();
    assertThat(store.load(ID)).isPresent();
  }

  @Test
  void theModelStreamIsClosedAfterEachTurn() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(new ToolCall("c1", "echo", echoArgs("hi"))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
                List.of(
                    new ModelEvent.TextChunk("Done."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN))));

    engine(
            provider,
            ToolRegistry.of(new EchoTool(true)),
            Approver.allowAll(),
            SessionStore.inMemory())
        .run(ID, new Event.UserSaid("echo hi"));

    assertThat(provider.closedCount).isEqualTo(2);
    assertThat(provider.openStreams).isZero();
    assertThat(provider.maxOpenStreams).isEqualTo(1);
  }

  @Test
  void aSecondRunContinuesTheSavedSession() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.TextChunk("Four."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN)),
                List.of(
                    new ModelEvent.TextChunk("Five."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN))));
    SessionStore store = SessionStore.inMemory();
    InProcessEngine engine = engine(provider, ToolRegistry.of(), Approver.allowAll(), store);

    engine.run(ID, new Event.UserSaid("what is 2+2?"));
    RunOutcome outcome = engine.run(ID, new Event.UserSaid("what is 2+3?"));

    RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
    assertThat(completed.state().messages())
        .containsExactly(
            Message.user("what is 2+2?"),
            Message.assistant(List.of(new TextBlock("Four."))),
            Message.user("what is 2+3?"),
            Message.assistant(List.of(new TextBlock("Five."))));
  }

  @Test
  void twoToolCallsInOneTurnBatchIntoOneResultMessage() {
    FakeProvider provider =
        new FakeProvider(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(new ToolCall("c1", "echo", echoArgs("a"))),
                    new ModelEvent.ToolUseEmitted(new ToolCall("c2", "echo", echoArgs("b"))),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
                List.of(
                    new ModelEvent.TextChunk("Done."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN))));

    RunOutcome outcome =
        engine(
                provider,
                ToolRegistry.of(new EchoTool(false)),
                Approver.allowAll(),
                SessionStore.inMemory())
            .run(ID, new Event.UserSaid("echo a and b"));

    RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
    assertThat(completed.state().messages()).hasSize(4);
    assertThat(completed.state().messages().get(2).content())
        .containsExactly(
            new ToolResultBlock("c1", "echoed:a", false),
            new ToolResultBlock("c2", "echoed:b", false));
  }

  /** A listener that fails mid-turn, to prove the run still persists what it reached. */
  private static final class ExplodingListener implements AgentEventListener {

    @Override
    public void onEvent(SessionId id, Event event, SessionState state) {
      if (event instanceof Event.ModelTurnEnded) {
        throw new IllegalStateException("listener blew up");
      }
    }
  }

  private static final class RecordingListener implements AgentEventListener {

    private final List<Event> events = new ArrayList<>();

    @Override
    public void onEvent(SessionId id, Event event, SessionState state) {
      events.add(event);
    }
  }
}
