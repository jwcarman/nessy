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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;

@DisplayName("What the console says about an agent")
class ConsoleNarrationTest {

  private static final AgentType CHAT = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final CallId CALL = new CallId("c1");

  @Test
  void calls_and_their_fates_are_noted_and_a_termination_ends_the_wait() {
    FakeConsole console = new FakeConsole();
    ConsoleNarration narration = new ConsoleNarration(AGENT, console);

    for (Narration event :
        List.of(
            new Narration.TurnStarted(new TurnId(1)),
            new Narration.Thinking(),
            new Narration.ActionsRequested(List.of(new ToolName("depth"))),
            new Narration.CallDenied(CALL, "not today"),
            new Narration.CallFailed(CALL, "boom"),
            new Narration.CallFinished(CALL),
            new Narration.ContentDelta("230"),
            new Narration.Terminated())) {
      narration.on(CHAT, AGENT, event);
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

    narration.on(CHAT, new AgentId(UUID.randomUUID()), new Narration.Answered());

    assertThat(console.written()).isEmpty();
  }

  @Test
  void a_blank_answer_prints_nothing_but_still_ends_the_turn() {
    FakeConsole console = new FakeConsole();
    ConsoleNarration narration = new ConsoleNarration(AGENT, console);

    narration.on(CHAT, AGENT, new Narration.Answered());

    assertThat(console.written()).isEmpty();
    assertThat(narration.spoke()).isFalse();
  }

  @Test
  void failures_and_refusals_are_not_the_terminals_business() {
    FakeConsole console = new FakeConsole();
    ConsoleNarration narration = new ConsoleNarration(AGENT, console);

    narration.on(CHAT, AGENT, new Narration.TurnFailed("the provider gave up"));
    narration.on(CHAT, AGENT, new Narration.TurnRefused("safety"));

    for (Narration quiet :
        List.of(
            new Narration.TurnEnded(new TurnId(1)),
            new Narration.Commentary("hmm"),
            new Narration.CallApproved(CALL),
            new Narration.ApprovalSought(CALL, "restart"),
            new Narration.ApprovalDeferred(CALL, "restart", java.time.Instant.EPOCH),
            new Narration.CallDeferred(CALL, new ToolName("t"), java.time.Instant.EPOCH),
            new Narration.ThinkingDelta("h"))) {
      narration.on(CHAT, AGENT, quiet);
    }
    assertThat(console.written()).isEmpty();
  }
}
