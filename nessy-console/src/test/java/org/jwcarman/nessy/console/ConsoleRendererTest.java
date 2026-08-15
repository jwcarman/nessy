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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.StringWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

class ConsoleRendererTest {

  private static final ToolCall CALL = new ToolCall("c1", "clock", emptyArguments());

  private static ObjectNode emptyArguments() {
    return JsonNodeFactory.instance.objectNode();
  }

  @AfterEach
  void clear_the_override_seam() {
    Ansi.overrideEnabled(null);
  }

  @Nested
  class With_styling_enabled {

    @Test
    void text_deltas_stream_plain_with_no_styling_at_all() {
      Ansi.overrideEnabled(true);
      StringWriter out = new StringWriter();
      TurnObserver observer = ConsoleRenderer.observer(out);

      observer.on(new TurnEvent.TextDelta("hello"));

      assertThat(out).hasToString("hello");
    }

    @Test
    void thinking_deltas_render_dim_italic() {
      Ansi.overrideEnabled(true);
      StringWriter out = new StringWriter();
      TurnObserver observer = ConsoleRenderer.observer(out);

      observer.on(new TurnEvent.ThinkingDelta("pondering"));

      assertThat(out).hasToString(Ansi.dim(Ansi.italic("pondering")));
    }

    @Test
    void a_requested_tool_call_renders_a_dim_line_with_its_name() {
      Ansi.overrideEnabled(true);
      StringWriter out = new StringWriter();
      TurnObserver observer = ConsoleRenderer.observer(out);

      observer.on(new TurnEvent.ToolCallRequested(CALL));

      assertThat(out).hasToString("\n" + Ansi.dim("⚙ tool: clock requested") + "\n");
    }

    @Test
    void a_completed_tool_call_renders_a_dim_line_with_its_name() {
      Ansi.overrideEnabled(true);
      StringWriter out = new StringWriter();
      TurnObserver observer = ConsoleRenderer.observer(out);

      observer.on(new TurnEvent.ToolCallCompleted(CALL, ToolResult.ok("14:00")));

      assertThat(out).hasToString("\n" + Ansi.dim("⚙ tool: clock completed") + "\n");
    }

    @Test
    void a_parked_tool_call_renders_a_dim_line_carrying_the_park_token() {
      Ansi.overrideEnabled(true);
      StringWriter out = new StringWriter();
      TurnObserver observer = ConsoleRenderer.observer(out);
      ParkToken token = new ParkToken("wait-1");

      observer.on(new TurnEvent.ToolCallParked(CALL, token));

      assertThat(out).hasToString("\n" + Ansi.dim("⚙ tool: clock parked (wait-1)") + "\n");
    }

    @Test
    void a_failed_turn_ending_renders_a_red_line_with_the_reason() {
      Ansi.overrideEnabled(true);
      StringWriter out = new StringWriter();
      TurnObserver observer = ConsoleRenderer.observer(out);

      observer.on(new TurnEvent.TurnEnded(ConversationStatus.FAILED, "too many tool errors"));

      assertThat(out).hasToString("\n" + Ansi.red("! too many tool errors") + "\n");
    }

    @Test
    void a_quiescent_turn_ending_renders_nothing() {
      Ansi.overrideEnabled(true);
      StringWriter out = new StringWriter();
      TurnObserver observer = ConsoleRenderer.observer(out);

      observer.on(new TurnEvent.TurnEnded(ConversationStatus.COMPLETE, null));

      assertThat(out.toString()).isEmpty();
    }
  }

  @Nested
  class With_styling_disabled {

    @Test
    void every_line_is_the_same_text_with_no_sgr_codes_at_all() {
      Ansi.overrideEnabled(false);
      StringWriter out = new StringWriter();
      TurnObserver observer = ConsoleRenderer.observer(out);

      observer.on(new TurnEvent.TextDelta("hello "));
      observer.on(new TurnEvent.ThinkingDelta("pondering"));
      observer.on(new TurnEvent.ToolCallRequested(CALL));
      observer.on(new TurnEvent.TurnEnded(ConversationStatus.FAILED, "boom"));

      assertThat(out).hasToString("hello pondering\n⚙ tool: clock requested\n\n! boom\n");
    }
  }
}
