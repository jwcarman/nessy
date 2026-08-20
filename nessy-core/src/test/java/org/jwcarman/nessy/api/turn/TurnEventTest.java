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
package org.jwcarman.nessy.api.turn;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class TurnEventTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "search", JsonNodeFactory.instance.objectNode());
  private static final Message ASSISTANT_MESSAGE =
      Message.assistant(List.of(new TextBlock("hello")));

  /**
   * One instance of every {@link TurnEvent} variant, mirroring {@code TurnObserverAdapterTest}'s.
   */
  private static List<TurnEvent> oneOfEveryVariant() {
    return List.of(
        new TurnEvent.TextDelta("prose"),
        new TurnEvent.ThinkingDelta("hmm"),
        new TurnEvent.RedactedThinking("opaque"),
        new TurnEvent.ToolCallRequested(CALL),
        new TurnEvent.ToolCallDecided(CALL, Decision.allow()),
        new TurnEvent.ToolCallCompleted(CALL, ToolResult.ok("done")),
        new TurnEvent.ToolCallProgressed(CALL, "halfway"),
        new TurnEvent.AssistantSaid(ASSISTANT_MESSAGE),
        new TurnEvent.TurnEnded(null));
  }

  @Test
  void a_noop_observer_accepts_every_event_without_complaint() {
    TurnObserver observer = TurnObserver.noop();

    assertThatCode(() -> oneOfEveryVariant().forEach(observer::on)).doesNotThrowAnyException();
  }

  @Test
  void text_delta_rejects_null_text() {
    assertThatThrownBy(() -> new TurnEvent.TextDelta(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void thinking_delta_rejects_null_text() {
    assertThatThrownBy(() -> new TurnEvent.ThinkingDelta(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void redacted_thinking_rejects_null_data() {
    assertThatThrownBy(() -> new TurnEvent.RedactedThinking(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void assistant_said_rejects_a_null_message() {
    assertThatThrownBy(() -> new TurnEvent.AssistantSaid(null))
        .isInstanceOf(NullPointerException.class);
  }
}
