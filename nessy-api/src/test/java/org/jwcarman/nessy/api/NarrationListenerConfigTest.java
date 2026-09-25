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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.inference.TurnId;
import org.jwcarman.nessy.inference.tool.CallId;
import org.jwcarman.nessy.inference.tool.ToolName;

@DisplayName("The listener builder")
class NarrationListenerConfigTest {

  private static final AgentType CHAT = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final CallId CALL = new CallId("c1");

  private static final List<Narration> EVERY_KIND =
      List.of(
          new Narration.TurnStarted(new TurnId(1)),
          new Narration.Thinking(),
          new Narration.Answered(),
          new Narration.TurnEnded(new TurnId(1)),
          new Narration.TurnFailed(),
          new Narration.TurnRefused(),
          new Narration.Commentary("hmm"),
          new Narration.ActionsRequested(List.of(new ToolName("t"))),
          new Narration.CallApproved(CALL),
          new Narration.CallDenied(CALL, "no"),
          new Narration.CallFinished(CALL),
          new Narration.CallFailed(CALL, "boom"),
          new Narration.Terminated(),
          new Narration.ApprovalSought(CALL, "restart"),
          new Narration.ApprovalDeferred(CALL, "restart", Instant.EPOCH),
          new Narration.CallDeferred(CALL, new ToolName("t"), Instant.EPOCH),
          new Narration.ThinkingDelta("h"),
          new Narration.ContentDelta("c"));

  @Test
  void every_kind_has_a_method_of_its_own_and_each_hears_only_its_kind() {
    List<String> heard = new ArrayList<>();
    NarrationListener listener =
        NarrationListener.of(
            on ->
                on.onTurnStarted((t, id, e) -> heard.add("started " + e.turn()))
                    .onThinking((t, id, e) -> heard.add("thinking"))
                    .onAnswered((t, id, e) -> heard.add("answered"))
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
            "started 1",
            "thinking",
            "answered",
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
    NarrationListener listener =
        NarrationListener.of(
            on -> on.agentType(CHAT).onAnswered((t, id, _) -> heard.add("answered")));

    listener.on(new AgentType("other"), AGENT, new Narration.Answered());
    listener.on(CHAT, AGENT, new Narration.Thinking());
    listener.on(CHAT, AGENT, new Narration.Answered());

    assertThat(heard).containsExactly("answered");
  }
}
