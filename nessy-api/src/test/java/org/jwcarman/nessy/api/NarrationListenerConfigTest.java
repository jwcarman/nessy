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
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;

@DisplayName("The listener builder")
class NarrationListenerConfigTest {

  private static final AgentType CHAT = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final TurnId TURN = new TurnId(1);
  private static final CallId CALL = new CallId("c1");
  private static final UUID KEY_ID = UUID.fromString("01999999-0000-7000-8000-000000000001");

  private static final List<Narration> EVERY_KIND =
      List.of(
          new Narration.TurnStarted(new TurnId(1)),
          new Narration.Thinking(),
          new Narration.Answered(new TurnId(1), Usage.unreported()),
          new Narration.TurnStopped(new TurnId(1), "too many calls"),
          new Narration.TurnFailed(
              new TurnId(1), FailureKind.PERMANENT, "the provider gave up", Usage.unreported()),
          new Narration.TurnRefused(new TurnId(1), "safety", Usage.unreported()),
          new Narration.InferenceRetried(
              new TurnId(1), FailureKind.TRANSIENT, "busy", Usage.unreported()),
          new Narration.Commentary("hmm"),
          new Narration.ActionsRequested(
              new TurnId(1),
              List.of(
                  new Narration.ActionsRequested.Call(
                      CALL, IdempotencyKey.of(KEY_ID), new ToolName("t"), "do t")),
              Usage.unreported()),
          new Narration.CallApproved(CALL, IdempotencyKey.of(KEY_ID)),
          new Narration.CallDenied(CALL, IdempotencyKey.of(KEY_ID), "no"),
          new Narration.CallFinished(CALL, IdempotencyKey.of(KEY_ID)),
          new Narration.CallFailed(CALL, IdempotencyKey.of(KEY_ID), "boom"),
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
                on.onTurnStarted((_, e) -> heard.add("started " + e.turn()))
                    .onThinking((_, e) -> heard.add("thinking"))
                    .onAnswered((_, e) -> heard.add("answered"))
                    .onTurnStopped((_, e) -> heard.add("stopped " + e.reason()))
                    .onTurnFailed((_, e) -> heard.add("failed"))
                    .onTurnRefused((_, e) -> heard.add("refused"))
                    .onInferenceRetried((_, e) -> heard.add("retried " + e.reason()))
                    .onCommentary((_, e) -> heard.add("commentary " + e.text()))
                    .onActionsRequested(
                        (_, e) -> heard.add("actions " + e.calls().getFirst().toolName()))
                    .onCallApproved((_, e) -> heard.add("approved " + e.callId()))
                    .onCallDenied((_, e) -> heard.add("denied " + e.reason()))
                    .onCallFinished((_, e) -> heard.add("finished " + e.callId()))
                    .onCallFailed((_, e) -> heard.add("call failed " + e.message()))
                    .onTerminated((_, e) -> heard.add("terminated"))
                    .onApprovalSought((_, e) -> heard.add("sought " + e.action()))
                    .onApprovalDeferred((_, e) -> heard.add("deferred " + e.until()))
                    .onCallDeferred((_, e) -> heard.add("call deferred " + e.toolName()))
                    .onThinkingDelta((_, e) -> heard.add("thinking delta " + e.text()))
                    .onContentDelta((_, e) -> heard.add("content delta " + e.text())));

    EVERY_KIND.forEach(event -> listener.on(heard(CHAT, event)));

    assertThat(heard)
        .containsExactly(
            "started 1",
            "thinking",
            "answered",
            "stopped too many calls",
            "failed",
            "refused",
            "retried busy",
            "commentary hmm",
            "actions t",
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
        NarrationListener.of(on -> on.agentType(CHAT).onAnswered((_, _) -> heard.add("answered")));

    listener.on(heard(new AgentType("other"), new Narration.Answered(TURN, Usage.unreported())));
    listener.on(heard(CHAT, new Narration.Thinking()));
    listener.on(heard(CHAT, new Narration.Answered(TURN, Usage.unreported())));

    assertThat(heard).containsExactly("answered");
  }

  @Test
  void a_handler_for_turn_endings_hears_each_way_a_turn_can_end() {
    List<Narration> heard = new ArrayList<>();
    NarrationListener listener =
        NarrationListener.of(c -> c.onTurnEnding((_, event) -> heard.add(event)));
    TurnId turn = new TurnId(1);
    List<Narration> endings =
        List.of(
            new Narration.Answered(turn, Usage.unreported()),
            new Narration.TurnRefused(turn, "safety", Usage.unreported()),
            new Narration.TurnFailed(turn, FailureKind.PERMANENT, "no", Usage.unreported()),
            new Narration.TurnStopped(turn, "limit"));

    endings.forEach(event -> listener.on(heard(CHAT, event)));
    listener.on(heard(CHAT, new Narration.Thinking()));

    assertThat(heard).containsExactlyElementsOf(endings);
  }

  /** An event as a listener is handed it: a story event at a place in the story, a signal alone. */
  private static Narrated heard(AgentType type, Narration event) {
    return switch (event) {
      case Narration.Story story -> Narrated.story(type, AGENT, story, new Seq(1), Instant.EPOCH);
      case Narration.Live live -> Narrated.live(type, AGENT, live);
    };
  }
}
