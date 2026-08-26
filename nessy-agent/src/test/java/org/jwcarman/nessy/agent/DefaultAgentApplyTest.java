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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.NoToolsExecutor;
import org.jwcarman.nessy.agent.support.RaceOnceStore;
import org.jwcarman.nessy.agent.support.RecordingMemory;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.ThrowingThenDelegatingMemory;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

class DefaultAgentApplyTest {

  /**
   * Fix round 1, item 5: reclaims every harness this test class built (directly or via {@link
   * org.jwcarman.nessy.agent.support.TestAgents} / {@code AgentFixture}) — each now owns a live
   * delivery-worker heartbeat (harness-first spec §4) that nothing else stops.
   */
  @AfterEach
  void shutdownTrackedHarnesses() {
    HarnessTeardown.shutdownAllTracked();
  }

  private static final ToolCall CALL_A =
      new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_B =
      new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());

  @Test
  void aFullTurnRunsObserveToIdle() {
    var f = new AgentFixture();
    f.model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("hello back")), List.of(), ModelResponseId.of("response-1")));
    f.agent.tell("hello");
    f.pump.pumpUntilQuiet();
    assertThat(f.store.load().phase()).isEqualTo(new Phase.Idle());
    assertThat(f.memory.remembered())
        .containsExactly(
            Message.user(List.of(new TextBlock("hello"))),
            Message.assistant(List.of(new TextBlock("hello back"))));
    assertThat(f.observer.applied()).hasSize(2);
  }

  @Test
  void theUserMessageIsInMemoryBeforeTheModelIsCalled() {
    var f = new AgentFixture();
    f.model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("ok")), List.of(), ModelResponseId.of("response-1")));
    f.agent.tell("hello");
    f.pump.pumpUntilQuiet();
    assertThat(f.model.memorySizesAtCall()).isNotEmpty();
    assertThat(f.model.memorySizesAtCall().getFirst()).isEqualTo(1);
  }

  @Test
  void aFanOutCommitsTheWholeUnitExactlyOnce() {
    var f = new AgentFixture();
    var turnBlocks =
        List.<ContentBlock>of(new ToolUseBlock(CALL_A, "sig-a"), new ToolUseBlock(CALL_B, null));
    f.model.enqueue(
        new ModelOutcome.Responded(
            turnBlocks, List.of(CALL_A, CALL_B), ModelResponseId.of("response-1")));
    f.model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("both done")), List.of(), ModelResponseId.of("response-2")));
    f.tools.answer("a", new ToolOutcome.Returned(ToolResult.ok("42")));
    f.tools.answer("b", new ToolOutcome.Returned(ToolResult.ok("restarted")));
    f.agent.tell("do both");
    f.pump.pumpUntilQuiet();
    assertThat(f.store.load().phase()).isEqualTo(new Phase.Idle());
    assertThat(f.tools.executed()).containsExactly(CALL_A, CALL_B);
    assertThat(f.memory.remembered())
        .containsExactly(
            Message.user(List.of(new TextBlock("do both"))),
            Message.assistant(turnBlocks),
            Message.toolResults(
                List.of(
                    new ToolResultBlock("a", "42", false),
                    new ToolResultBlock("b", "restarted", false))),
            Message.assistant(List.of(new TextBlock("both done"))));
  }

  @Test
  void aDuplicateToolDeliveryIsIgnoredAndWritesNothing() {
    var f = new AgentFixture();
    var turnBlocks = List.<ContentBlock>of(new ToolUseBlock(CALL_A, null));
    f.model.enqueue(
        new ModelOutcome.Responded(turnBlocks, List.of(CALL_A), ModelResponseId.of("response-1")));
    f.model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("done")), List.of(), ModelResponseId.of("response-2")));
    f.tools.answer("a", new ToolOutcome.Returned(ToolResult.ok("42")));
    f.agent.tell("go");
    f.pump.pumpUntilQuiet();
    var rememberedBefore = f.memory.remembered();
    f.agent.deliver(
        new AgentEvent.ToolFinished(
            CALL_A, Optional.empty(), new ToolOutcome.Returned(ToolResult.ok("42-again"))));
    f.pump.pumpUntilQuiet();
    assertThat(f.observer.ignored()).hasSize(1);
    assertThat(f.memory.remembered()).isEqualTo(rememberedBefore);
  }

  @Test
  void aModelFailureEndsTheTurnQuietlyInBand() {
    var f = new AgentFixture();
    f.model.enqueue(new ModelOutcome.Failed("overloaded"));
    f.agent.tell("hello");
    f.pump.pumpUntilQuiet();
    assertThat(f.store.load().phase()).isEqualTo(new Phase.Idle());
    assertThat(f.memory.remembered())
        .containsExactly(Message.user(List.of(new TextBlock("hello"))));
    assertThat(f.observer.applied()).hasSize(2);
  }

  @Test
  void aCompletionThatLosesTheRaceIsReHandledAgainstFreshState() {
    // Seed a store mid-fan-out: AwaitingTools{a,b}, and let a competitor apply a's result
    // out-of-band just before b's save — computed with the pure machine, no threads needed.
    var inner =
        new SubstrateAgentStateStore(
            new InMemorySubstrate(), "agent", Clock.systemUTC(), TestMappers.plainlyPinned());
    var turn =
        Message.assistant(
            List.<ContentBlock>of(new ToolUseBlock(CALL_A, null), new ToolUseBlock(CALL_B, null)));
    var awaiting =
        new Phase.AwaitingTools(
            turn,
            Map.of("a", new ToolCallState.Running(), "b", new ToolCallState.Running()),
            ModelResponseId.of("response-1"));
    inner.save(new State(awaiting, 0L)); // now at v1
    var aFinished =
        new AgentEvent.ToolFinished(
            CALL_A, Optional.empty(), new ToolOutcome.Returned(ToolResult.ok("42")));
    var aTransition = awaiting.handle(aFinished);
    var f = new AgentFixture(new RaceOnceStore(inner, new State(aTransition.next(), 1L)), false);
    // The competitor's own fold, off-thread from this test's real agent, also remembers its
    // ToolExchange BEFORE its own commit (remembrance spec §1 law 1) — the same
    // ToolFoldRemembrance mapping the real agent below uses, so the two converge on shared keys
    // exactly as two racing DeliveryWorker/DefaultAgent instances over one substrate would.
    ToolFoldRemembrance.remember(
        f.memory,
        AgentType.of("fixture"),
        AgentId.of("agent"),
        awaiting,
        CALL_A,
        new ToolOutcome.Returned(ToolResult.ok("42")),
        aTransition);
    f.model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("done")), List.of(), ModelResponseId.of("response-2")));
    f.agent.deliver(
        new AgentEvent.ToolFinished(
            CALL_B, Optional.empty(), new ToolOutcome.Returned(ToolResult.ok("ok"))));
    f.pump.pumpUntilQuiet();
    // b lost its first save, re-handled against the competitor's state, and correctly closed
    // the unit: exactly one commit of turn + results, exactly one model call.
    assertThat(f.memory.remembered())
        .containsExactly(
            turn,
            Message.toolResults(
                List.of(
                    new ToolResultBlock("a", "42", false), new ToolResultBlock("b", "ok", false))),
            Message.assistant(List.of(new TextBlock("done"))));
    assertThat(f.model.callCount()).isEqualTo(1);
    assertThat(f.store.load().phase()).isEqualTo(new Phase.Idle());
  }

  @Test
  void aHandleFailureIsNarratedAndDroppedWithThePhaseUnchanged() {
    // The model responds with tool calls but omits the ToolUseBlock for CALL_A from its content —
    // AwaitingTools's constructor rejects that inside handle(), and the failure must be narrated
    // and dropped rather than escaping the pump.
    var f = new AgentFixture();
    f.model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("no tool blocks")),
            List.of(CALL_A),
            ModelResponseId.of("response-1")));
    f.agent.tell("go");
    f.pump.pumpUntilQuiet();
    assertThat(f.observer.applyFailures()).hasSize(1);
    assertThat(f.store.load().phase()).isEqualTo(new Phase.AwaitingModel());
    // deliver rethrows after narrating now (tool-context-defer spec §3); the throw ends the model
    // executor's task and nothing else, exactly as a real thread pool would treat it.
    assertThat(f.pump.failures()).hasSize(1);
  }

  /**
   * {@code deliver} narrates and then RETHROWS (tool-context-defer spec §3). Every executor-side
   * caller runs inside a task where the narration was already the only trace, so nothing changes
   * for them; what the rethrow buys is the {@code defer()} doors, which promise that an id they
   * hand back is an id the scope names.
   */
  @Test
  void aFoldThatCannotCommitIsNarratedOnceAndThenReachesItsCaller() {
    var store =
        new SubstrateAgentStateStore(
            new InMemorySubstrate(), "agent", Clock.systemUTC(), TestMappers.plainlyPinned());
    var failures = new ArrayList<AgentEvent>();
    var agent =
        TestAgents.<String>wired(
            new ThrowingThenDelegatingMemory(new RecordingMemory(), 1),
            store,
            new NoopBacklog(),
            text -> List.of(new TextBlock(text)),
            sink -> {},
            new NoToolsExecutor(),
            new FailureRecorder(failures),
            false,
            StalenessPolicy.never());
    var observed = new AgentEvent.Observed(List.of(new TextBlock("hello")));

    assertThatThrownBy(() -> agent.deliver(observed)).isInstanceOf(IllegalStateException.class);

    assertThat(failures).containsExactly(observed);
    assertThat(store.load().phase()).isEqualTo(new Phase.Idle());
  }

  /**
   * The inline-executor shape (tool-context-defer spec §3, fix round 2): {@code
   * HarnessConfig.executor(Runnable::run)} runs a dispatched effect on the delivering thread, so
   * the model call's own {@code deliver} re-enters on top of the observation's. When that NESTED
   * fold fails, exactly one {@code applyFailed} must describe it — the nested frame's, naming the
   * event that actually failed. Before the commit/follow split, the outer frame narrated the same
   * failure a second time against {@code Observed}, the event that had already committed fine.
   */
  @Test
  void aNestedFoldFailureUnderAnInlineExecutorIsNarratedOnceByTheFrameThatFailed() {
    var store =
        new SubstrateAgentStateStore(
            new InMemorySubstrate(), "agent", Clock.systemUTC(), TestMappers.plainlyPinned());
    var failures = new ArrayList<AgentEvent>();
    var queue = new ArrayDeque<String>();
    Backlog<String> backlog =
        new Backlog<>() {
          @Override
          public void add(String observation) {
            queue.add(observation);
          }

          @Override
          public Optional<String> poll() {
            return Optional.ofNullable(queue.poll());
          }
        };
    var responded =
        new ModelOutcome.Responded(
            List.of(new TextBlock("hello back")), List.of(), ModelResponseId.of("response-1"));
    var agent =
        TestAgents.<String>wired(
            new RefusesTheAssistantTurn(new RecordingMemory()),
            store,
            backlog,
            text -> List.of(new TextBlock(text)),
            // the inline executor, in one line: the model "call" delivers on the caller's thread
            sink -> sink.deliver(new AgentEvent.ModelFinished(responded)),
            new NoToolsExecutor(),
            new FailureRecorder(failures),
            false,
            StalenessPolicy.never());

    assertThatThrownBy(() -> agent.tell("hello")).isInstanceOf(IllegalStateException.class);

    assertThat(failures).hasSize(1);
    assertThat(failures.getFirst()).isInstanceOf(AgentEvent.ModelFinished.class);
    // the observation itself committed cleanly — only the nested model fold failed
    assertThat(store.load().phase()).isEqualTo(new Phase.AwaitingModel());
    // and it stays committed: this runs through drainOne, whose requeue arms guard the COMMIT
    // only. Moving follow() back inside that try would put the observation back on the backlog
    // after it had already been applied, and the next drain would double-apply it.
    assertThat(queue).isEmpty();
  }

  /** Remembers everything except an assistant turn, which it refuses — a Memory half down. */
  private record RefusesTheAssistantTurn(Memory delegate) implements Memory {
    @Override
    public void remember(Remembrance remembrance) {
      if (remembrance instanceof Remembrance.AssistantMessage) {
        throw new IllegalStateException("memory refused the assistant turn");
      }
      delegate.remember(remembrance);
    }

    @Override
    public Context recall() {
      return delegate.recall();
    }
  }

  private static final class NoopBacklog implements Backlog<String> {
    @Override
    public void add(String observation) {}

    @Override
    public Optional<String> poll() {
      return Optional.empty();
    }
  }

  /** Records only {@code applyFailed}; every other callback is a silent no-op. */
  private record FailureRecorder(List<AgentEvent> narrated) implements HarnessObserver {
    @Override
    public void applied(AgentId id, AgentEvent event, Transition transition) {
      // silent: only applyFailed is recorded
    }

    @Override
    public void ignored(AgentId id, AgentEvent event) {
      // silent: only applyFailed is recorded
    }

    @Override
    public void renderFailed(AgentId id, Object observation, RuntimeException error) {
      // silent: only applyFailed is recorded
    }

    @Override
    public void applyFailed(AgentId id, AgentEvent event, RuntimeException error) {
      narrated.add(event);
    }

    @Override
    public void reFired(AgentId id, List<Effect> effects) {
      // silent: only applyFailed is recorded
    }

    @Override
    public void observationRequeued(AgentId id, Object observation) {
      // silent: only applyFailed is recorded
    }
  }

  @Test
  void theStateIsSavedBeforeAnyEffectIsDispatched() {
    var store =
        new SubstrateAgentStateStore(
            new InMemorySubstrate(), "agent", Clock.systemUTC(), TestMappers.plainlyPinned());
    var versionsAtCall = new ArrayList<Long>();
    var queue = new ArrayDeque<String>();
    Backlog<String> backlog =
        new Backlog<>() {
          @Override
          public void add(String observation) {
            queue.add(observation);
          }

          @Override
          public Optional<String> poll() {
            return Optional.ofNullable(queue.poll());
          }
        };
    var agent =
        TestAgents.<String>wired(
            new RecordingMemory(),
            store,
            backlog,
            text -> List.of(new TextBlock(text)),
            sink -> versionsAtCall.add(store.load().version()),
            new NoToolsExecutor(),
            HarnessObserver.noop(),
            false,
            StalenessPolicy.never());
    agent.tell("hi");
    assertThat(versionsAtCall).isNotEmpty();
    assertThat(versionsAtCall.getFirst()).isEqualTo(1L); // post-save version, not the loaded 0
  }
}
