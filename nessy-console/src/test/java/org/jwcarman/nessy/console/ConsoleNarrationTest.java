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
package org.jwcarman.nessy.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.FailureKind;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;

@DisplayName("What the console says about an agent")
class ConsoleNarrationTest {

  private static final AgentType CHAT = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
  private static final CallId CALL = new CallId("c1");

  @Test
  void calls_and_their_fates_are_noted_and_a_termination_ends_the_wait() {
    FakeConsole console = new FakeConsole();
    ConsoleNarration narration = new ConsoleNarration(AGENT, console);

    for (Narration event :
        List.of(
            new Narration.TurnStarted(new TurnId(1)),
            new Narration.Thinking(),
            new Narration.ActionsRequested(
                new TurnId(1),
                List.of(
                    new Narration.ActionsRequested.Call(
                        new CallId("c1"), KEY, new ToolName("depth"), "depth")),
                Usage.unreported()),
            new Narration.CallDenied(CALL, KEY, "not today", Optional.empty()),
            new Narration.CallFailed(CALL, KEY, "boom"),
            new Narration.CallFinished(CALL, KEY),
            new Narration.ContentDelta("230"),
            new Narration.Terminated())) {
      narration.on(Envelopes.of(CHAT, AGENT, event));
    }

    assertThat(console.written())
        .contains("[thinking]")
        .contains("c1 failed: boom")
        .contains("not today")
        .contains("230");
    assertThat(narration.spoke()).isTrue();
  }

  @Test
  void another_agents_events_are_not_this_terminals_business() {
    FakeConsole console = new FakeConsole();
    ConsoleNarration narration = new ConsoleNarration(AGENT, console);

    narration.on(
        Envelopes.of(
            CHAT,
            new AgentId(UUID.randomUUID()),
            new Narration.Answered(new TurnId(1), Usage.unreported())));

    assertThat(console.written()).isEmpty();
  }

  @Test
  void a_blank_answer_prints_nothing_but_still_ends_the_turn() {
    FakeConsole console = new FakeConsole();
    ConsoleNarration narration = new ConsoleNarration(AGENT, console);

    narration.on(
        Envelopes.of(CHAT, AGENT, new Narration.Answered(new TurnId(1), Usage.unreported())));

    assertThat(console.written()).isEmpty();
    assertThat(narration.spoke()).isFalse();
  }

  @Test
  void failures_and_refusals_are_not_the_terminals_business() {
    FakeConsole console = new FakeConsole();
    ConsoleNarration narration = new ConsoleNarration(AGENT, console);

    narration.on(
        Envelopes.of(
            CHAT,
            AGENT,
            new Narration.TurnFailed(
                new TurnId(1), FailureKind.PERMANENT, "the provider gave up", Usage.unreported())));
    narration.on(
        Envelopes.of(
            CHAT, AGENT, new Narration.TurnRefused(new TurnId(1), "safety", Usage.unreported())));
    narration.on(
        Envelopes.of(CHAT, AGENT, new Narration.TurnStopped(new TurnId(1), "too many calls")));

    for (Narration quiet :
        List.of(
            new Narration.InferenceRetried(
                new TurnId(1), FailureKind.TRANSIENT, "busy", Usage.unreported()),
            new Narration.Commentary("hmm"),
            new Narration.CallApproved(CALL, KEY, Optional.empty()),
            new Narration.ApprovalSought(CALL, "restart"),
            new Narration.ApprovalDeferred(CALL, "restart", java.time.Instant.EPOCH),
            new Narration.CallDeferred(CALL, new ToolName("t"), java.time.Instant.EPOCH),
            new Narration.ThinkingDelta("h"))) {
      narration.on(Envelopes.of(CHAT, AGENT, quiet));
    }
    assertThat(console.written()).isEmpty();
  }
}
