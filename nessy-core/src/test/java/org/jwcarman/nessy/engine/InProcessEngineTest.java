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
package org.jwcarman.nessy.engine;

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
import org.jwcarman.nessy.approval.ApprovalRequest;
import org.jwcarman.nessy.approval.ApproveEverything;
import org.jwcarman.nessy.approval.Approver;
import org.jwcarman.nessy.approval.DenyEverything;
import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.Decision;
import org.jwcarman.nessy.core.Event;
import org.jwcarman.nessy.core.Message;
import org.jwcarman.nessy.core.ParkToken;
import org.jwcarman.nessy.core.Reducer;
import org.jwcarman.nessy.core.Role;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.SessionState;
import org.jwcarman.nessy.core.SessionStatus;
import org.jwcarman.nessy.core.StopReason;
import org.jwcarman.nessy.core.TextBlock;
import org.jwcarman.nessy.core.ToolCall;
import org.jwcarman.nessy.core.ToolResult;
import org.jwcarman.nessy.core.ToolResultBlock;
import org.jwcarman.nessy.model.Capability;
import org.jwcarman.nessy.model.ModelEvent;
import org.jwcarman.nessy.model.ModelProvider;
import org.jwcarman.nessy.model.ModelRequest;
import org.jwcarman.nessy.model.ModelStream;
import org.jwcarman.nessy.session.InMemorySessionStore;
import org.jwcarman.nessy.session.SessionStore;
import org.jwcarman.nessy.tool.MapToolRegistry;
import org.jwcarman.nessy.tool.Tool;
import org.jwcarman.nessy.tool.ToolContext;
import org.jwcarman.nessy.tool.ToolRegistry;

class InProcessEngineTest {

  private static final SessionId ID = new SessionId("s1");
  private static final AgentConfig CONFIG = new AgentConfig("fake-model", "be helpful", 1024);

  /** A model that replays scripted turns, one per call. */
  private static final class FakeProvider implements ModelProvider {

    private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();

    // Takes a List of turns rather than varargs: generic varargs would raise an
    // unchecked warning, and this project forbids @SuppressWarnings outright.
    FakeProvider(List<List<ModelEvent>> scripted) {
      turns.addAll(scripted);
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      Iterator<ModelEvent> events = turns.removeFirst().iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          // nothing to release
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
        engine(provider, MapToolRegistry.of(), new ApproveEverything(), new InMemorySessionStore())
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
                MapToolRegistry.of(new EchoTool(true)),
                new ApproveEverything(),
                new InMemorySessionStore())
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
    CountingApprover approver = new CountingApprover(new ApproveEverything());

    engine(provider, MapToolRegistry.of(new EchoTool(false)), approver, new InMemorySessionStore())
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
                MapToolRegistry.of(new EchoTool(true)),
                new DenyEverything("not allowed"),
                new InMemorySessionStore())
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
        engine(provider, MapToolRegistry.of(), new ApproveEverything(), new InMemorySessionStore())
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
        engine(
                provider,
                MapToolRegistry.of(exploding),
                new ApproveEverything(),
                new InMemorySessionStore())
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

    engine(
            provider,
            MapToolRegistry.of(),
            new ApproveEverything(),
            new InMemorySessionStore(),
            listener)
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
    SessionStore store = new InMemorySessionStore();

    engine(provider, MapToolRegistry.of(), new ApproveEverything(), store)
        .run(ID, new Event.UserSaid("what is 2+2?"));

    assertThat(store.load(ID)).isPresent();
    assertThat(store.load(ID).orElseThrow().status()).isEqualTo(SessionStatus.COMPLETE);
  }

  @Test
  void resumeIsRefusedBecauseThisEngineNeverParks() {
    FakeProvider provider = new FakeProvider(List.of());

    assertThatThrownBy(
            () ->
                engine(
                        provider,
                        MapToolRegistry.of(),
                        new ApproveEverything(),
                        new InMemorySessionStore())
                    .resume(ID, ParkToken.random(), new Event.UserSaid("x")))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("DurableEngine");
  }

  private static final class RecordingListener implements AgentEventListener {

    private final List<Event> events = new ArrayList<>();

    @Override
    public void onEvent(SessionId id, Event event, SessionState state) {
      events.add(event);
    }
  }
}
