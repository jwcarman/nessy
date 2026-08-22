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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.store.StoredAgentStateStore;
import org.jwcarman.nessy.agent.support.TestClock;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.store.InMemoryScopedStore;

class DefaultAgentRecoveryTest {

  private static final Instant T0 = Instant.parse("2026-08-20T12:00:00Z");
  private static final Duration THRESHOLD = Duration.ofMinutes(5);
  private static final ToolCall CALL_A =
      new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());

  private static AgentFixture stalled(Phase phase, TestClock clock) {
    var store = new StoredAgentStateStore(new InMemoryScopedStore(clock), "agent", clock);
    store.save(new State(phase, 0L));
    return new AgentFixture(store, false, StalenessPolicy.after(THRESHOLD, clock));
  }

  @Test
  void aFreshTurnIsLeftAlone() {
    var clock = new TestClock(T0);
    var f = stalled(new Phase.AwaitingModel(), clock);
    clock.advance(Duration.ofSeconds(30));
    f.agent.drive();
    assertThat(f.model.callCount()).isZero();
  }

  @Test
  void aStaleModelCallIsReFired() {
    var clock = new TestClock(T0);
    var f = stalled(new Phase.AwaitingModel(), clock);
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("recovered")), List.of()));
    clock.advance(Duration.ofMinutes(6));
    f.agent.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.model.callCount()).isEqualTo(1);
    assertThat(f.store.load().phase()).isEqualTo(new Phase.Idle());
  }

  @Test
  void aTurnStalledForExactlyTheThresholdIsReFired() {
    // Pins the inclusive boundary (>=): advancing by exactly the threshold must still count as
    // stale, not merely one tick past it.
    var clock = new TestClock(T0);
    var f = stalled(new Phase.AwaitingModel(), clock);
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("recovered")), List.of()));
    clock.advance(THRESHOLD);
    f.agent.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.model.callCount()).isEqualTo(1);
  }

  @Test
  void aStaleFanOutReFiresOnlyThePendingCallsWithTheirFullArguments() {
    var clock = new TestClock(T0);
    var turn = Message.assistant(List.<ContentBlock>of(new ToolUseBlock(CALL_A, "sig-a")));
    var f = stalled(new Phase.AwaitingTools(turn, Set.of("a"), List.of()), clock);
    f.tools.answer("a", new ToolOutcome.Returned(ToolResult.ok("42")));
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("done")), List.of()));
    clock.advance(Duration.ofMinutes(6));
    f.agent.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.tools.executed()).containsExactly(CALL_A);
    assertThat(f.store.load().phase()).isEqualTo(new Phase.Idle());
  }

  @Test
  void aStaleIdleScopeJustDrains() {
    var clock = new TestClock(T0);
    var store = new StoredAgentStateStore(new InMemoryScopedStore(clock), "agent", clock);
    var f = new AgentFixture(store, false, StalenessPolicy.after(THRESHOLD, clock));
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("ok")), List.of()));
    f.backlogQueue.add("waiting");
    clock.advance(Duration.ofHours(1));
    f.agent.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).isEmpty();
    assertThat(f.model.callCount()).isEqualTo(1);
  }

  @Test
  void aReFireIsNarrated() {
    var clock = new TestClock(T0);
    var f = stalled(new Phase.AwaitingModel(), clock);
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("ok")), List.of()));
    clock.advance(Duration.ofMinutes(6));
    f.agent.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.observer.reFires()).containsExactly(List.of(new Effect.CallModel()));
  }
}
