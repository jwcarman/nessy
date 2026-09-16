package org.jwcarman.nessy.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;

@DisplayName("What the console says about an agent")
class ConsoleNarrationTest {

  private static final AgentType CHAT = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final CallId CALL = new CallId("c1");

  @Test
  void calls_and_their_fates_are_noted_and_a_termination_ends_the_wait() throws Exception {
    FakeConsole console = new FakeConsole();
    ConsoleNarration narration = new ConsoleNarration(AGENT, console);

    for (AgentEvent event :
        List.of(
            new AgentEvent.TurnStarted(new TurnId(1), "hi"),
            new AgentEvent.Thinking(),
            new AgentEvent.ActionsRequested(List.of(new ToolName("depth"))),
            new AgentEvent.CallDenied(CALL, "not today"),
            new AgentEvent.CallFailed(CALL, "boom"),
            new AgentEvent.CallFinished(CALL),
            new AgentEvent.ContentDelta("230"),
            new AgentEvent.Terminated())) {
      narration.on(CHAT, AGENT, event);
    }

    assertThat(console.written())
        .contains("[thinking]")
        .contains("c1 failed: boom")
        .contains("not today")
        .contains("230");
    assertThat(narration.spoke()).isTrue();
    assertThat(narration.awaitEnding(Duration.ofSeconds(1)))
        .contains(ConsoleNarration.Ending.TERMINATED);
  }

  @Test
  void another_agents_events_are_not_this_terminals_business() throws Exception {
    FakeConsole console = new FakeConsole();
    ConsoleNarration narration = new ConsoleNarration(AGENT, console);

    narration.on(CHAT, new AgentId(UUID.randomUUID()), new AgentEvent.Answered("elsewhere"));

    assertThat(console.written()).isEmpty();
    assertThat(narration.awaitEnding(Duration.ofMillis(50))).isEmpty();
  }
}
