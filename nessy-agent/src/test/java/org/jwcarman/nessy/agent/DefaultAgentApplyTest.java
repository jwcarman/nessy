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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.LatentSink;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RaceOnceStore;
import org.jwcarman.nessy.agent.support.RecordingMemory;
import org.jwcarman.nessy.agent.support.RecordingObserver;
import org.jwcarman.nessy.agent.support.ScriptedModelExecutor;
import org.jwcarman.nessy.agent.support.ScriptedToolExecutor;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class DefaultAgentApplyTest {

  private static final ToolCall CALL_A =
      new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_B =
      new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());

  /** One fully-wired agent on a pump; the fixture is the test's vocabulary. */
  static final class Fixture {
    final PumpedExecutor pump = new PumpedExecutor();
    final LatentSink sink = new LatentSink();
    final RecordingMemory memory = new RecordingMemory();
    final RecordingObserver observer = new RecordingObserver();
    final ScriptedModelExecutor model = new ScriptedModelExecutor(pump, sink, memory);
    final ScriptedToolExecutor tools = new ScriptedToolExecutor(pump, sink);
    final Deque<String> backlogQueue = new ArrayDeque<>();
    final Backlog<String> backlog =
        new Backlog<>() {
          @Override
          public void add(String observation) {
            backlogQueue.add(observation);
          }

          @Override
          public Optional<String> poll() {
            return Optional.ofNullable(backlogQueue.poll());
          }
        };
    final AgentStateStore store;
    final DefaultAgent<String> agent;

    Fixture(AgentStateStore store, boolean drainOnIdle) {
      this.store = store;
      this.agent =
          new DefaultAgent<>(
              new AgentWiring<>(
                  memory,
                  store,
                  backlog,
                  text -> List.of(new TextBlock(text)),
                  model,
                  tools,
                  observer,
                  drainOnIdle,
                  Duration.ofMinutes(5),
                  Clock.systemUTC()));
      sink.bind(agent::deliver);
    }

    Fixture() {
      this(new InMemoryAgentStateStore(), false);
    }
  }

  @Test
  void aFullTurnRunsObserveToIdle() {
    var f = new Fixture();
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("hello back")), List.of()));
    f.agent.observe("hello");
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
    var f = new Fixture();
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("ok")), List.of()));
    f.agent.observe("hello");
    f.pump.pumpUntilQuiet();
    assertThat(f.model.memorySizesAtCall()).isNotEmpty();
    assertThat(f.model.memorySizesAtCall().getFirst()).isEqualTo(1);
  }

  @Test
  void aFanOutCommitsTheWholeUnitExactlyOnce() {
    var f = new Fixture();
    var turnBlocks =
        List.<ContentBlock>of(new ToolUseBlock(CALL_A, "sig-a"), new ToolUseBlock(CALL_B, null));
    f.model.enqueue(new ModelOutcome.Responded(turnBlocks, List.of(CALL_A, CALL_B)));
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("both done")), List.of()));
    f.tools.answer("a", new ToolOutcome.Returned(ToolResult.ok("42")));
    f.tools.answer("b", new ToolOutcome.Returned(ToolResult.ok("restarted")));
    f.agent.observe("do both");
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
    var f = new Fixture();
    var turnBlocks = List.<ContentBlock>of(new ToolUseBlock(CALL_A, null));
    f.model.enqueue(new ModelOutcome.Responded(turnBlocks, List.of(CALL_A)));
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("done")), List.of()));
    f.tools.answer("a", new ToolOutcome.Returned(ToolResult.ok("42")));
    f.agent.observe("go");
    f.pump.pumpUntilQuiet();
    var rememberedBefore = f.memory.remembered();
    f.agent.deliver(
        new AgentEvent.ToolFinished(CALL_A, new ToolOutcome.Returned(ToolResult.ok("42-again"))));
    f.pump.pumpUntilQuiet();
    assertThat(f.observer.ignored()).hasSize(1);
    assertThat(f.memory.remembered()).isEqualTo(rememberedBefore);
  }

  @Test
  void aModelFailureEndsTheTurnQuietlyInBand() {
    var f = new Fixture();
    f.model.enqueue(new ModelOutcome.Failed("overloaded"));
    f.agent.observe("hello");
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
    var inner = new InMemoryAgentStateStore();
    var turn =
        Message.assistant(
            List.<ContentBlock>of(new ToolUseBlock(CALL_A, null), new ToolUseBlock(CALL_B, null)));
    var awaiting = new Phase.AwaitingTools(turn, Set.of("a", "b"), List.of());
    inner.save(new State(awaiting, 0L)); // now at v1
    var aFinished =
        new AgentEvent.ToolFinished(CALL_A, new ToolOutcome.Returned(ToolResult.ok("42")));
    var competitorState = new State(awaiting.handle(aFinished).next(), 1L);
    var f = new Fixture(new RaceOnceStore(inner, competitorState), false);
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("done")), List.of()));
    f.agent.deliver(
        new AgentEvent.ToolFinished(CALL_B, new ToolOutcome.Returned(ToolResult.ok("ok"))));
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
}
