package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;

@DisplayName("The listener builder")
class AgentEventListenerConfigTest {

  private static final AgentType CHAT = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final CallId CALL = new CallId("c1");

  private static final List<AgentEvent> EVERY_KIND =
      List.of(
          new AgentEvent.TurnStarted(new TurnId(1), "hi"),
          new AgentEvent.Thinking(),
          new AgentEvent.Answered("hello"),
          new AgentEvent.TurnEnded(new TurnId(1)),
          new AgentEvent.TurnFailed(),
          new AgentEvent.TurnRefused(),
          new AgentEvent.Commentary("hmm"),
          new AgentEvent.ActionsRequested(List.of(new ToolName("t"))),
          new AgentEvent.CallApproved(CALL),
          new AgentEvent.CallDenied(CALL, "no"),
          new AgentEvent.CallFinished(CALL),
          new AgentEvent.CallFailed(CALL, "boom"),
          new AgentEvent.Terminated(),
          new AgentEvent.ApprovalSought(CALL, "restart"),
          new AgentEvent.ApprovalDeferred(CALL, "restart", Instant.EPOCH),
          new AgentEvent.CallDeferred(CALL, new ToolName("t"), Instant.EPOCH),
          new AgentEvent.ThinkingDelta("h"),
          new AgentEvent.ContentDelta("c"));

  @Test
  void every_kind_has_a_method_of_its_own_and_each_hears_only_its_kind() {
    List<String> heard = new ArrayList<>();
    AgentEventListener listener =
        AgentEventListener.of(
            on ->
                on.onTurnStarted((t, id, e) -> heard.add("started " + e.observation()))
                    .onThinking((t, id, e) -> heard.add("thinking"))
                    .onAnswered((t, id, e) -> heard.add("answered " + e.text()))
                    .onTurnEnded((t, id, e) -> heard.add("ended " + e.turn()))
                    .onTurnFailed((t, id, e) -> heard.add("failed"))
                    .onTurnRefused((t, id, e) -> heard.add("refused"))
                    .onCommentary((t, id, e) -> heard.add("commentary " + e.text()))
                    .onActionsRequested((t, id, e) -> heard.add("actions " + e.toolNames()))
                    .onCallApproved((t, id, e) -> heard.add("approved " + e.callId()))
                    .onCallDenied((t, id, e) -> heard.add("denied " + e.reason()))
                    .onCallFinished((t, id, e) -> heard.add("finished " + e.callId()))
                    .onCallFailed((t, id, e) -> heard.add("call failed " + e.message()))
                    .onTerminated((t, id, e) -> heard.add("terminated"))
                    .onApprovalSought((t, id, e) -> heard.add("sought " + e.action()))
                    .onApprovalDeferred((t, id, e) -> heard.add("deferred " + e.until()))
                    .onCallDeferred((t, id, e) -> heard.add("call deferred " + e.toolName()))
                    .onThinkingDelta((t, id, e) -> heard.add("thinking delta " + e.text()))
                    .onContentDelta((t, id, e) -> heard.add("content delta " + e.text())));

    EVERY_KIND.forEach(event -> listener.on(CHAT, AGENT, event));

    assertThat(heard)
        .containsExactly(
            "started hi",
            "thinking",
            "answered hello",
            "ended 1",
            "failed",
            "refused",
            "commentary hmm",
            "actions [t]",
            "approved c1",
            "denied no",
            "finished c1",
            "call failed boom",
            "terminated",
            "sought restart",
            "deferred 1970-01-01T00:00:00Z",
            "call deferred t",
            "thinking delta h",
            "content delta c");
  }

  @Test
  void a_type_filter_keeps_other_agents_out_and_a_kind_nobody_asked_about_is_ignored() {
    List<String> heard = new ArrayList<>();
    AgentEventListener listener =
        AgentEventListener.of(
            on -> on.agentType(CHAT).onAnswered((t, id, e) -> heard.add(e.text())));

    listener.on(new AgentType("other"), AGENT, new AgentEvent.Answered("not for us"));
    listener.on(CHAT, AGENT, new AgentEvent.Thinking());
    listener.on(CHAT, AGENT, new AgentEvent.Answered("for us"));

    assertThat(heard).containsExactly("for us");
  }
}
