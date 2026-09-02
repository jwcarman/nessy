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
package org.jwcarman.nessy.spi.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.block.CommentaryBlock;
import org.jwcarman.nessy.api.block.ProviderBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Turning a model talking into the message it said.
 *
 * <p>The fold both doors share, so the blocking one and the streaming one cannot disagree about
 * what a sequence of chunks meant.
 */
@DisplayName("Assembling a reply from a stream")
class ModelRepliesTest {

  private static final Usage COST = new Usage(10, 5);

  private static ModelStream saying(ModelEvent... events) {
    List<ModelEvent> list = List.of(events);
    return new ModelStream() {
      @Override
      public Iterator<ModelEvent> iterator() {
        return list.iterator();
      }

      @Override
      public void close() {
        // nothing to release
      }
    };
  }

  private static ModelResult.Answered answered(ModelResult result) {
    assertThat(result).isInstanceOf(ModelResult.Answered.class);
    return (ModelResult.Answered) result;
  }

  private static ModelResult.Asked asked(ModelResult result) {
    assertThat(result).isInstanceOf(ModelResult.Asked.class);
    return (ModelResult.Asked) result;
  }

  @Test
  @DisplayName("prose arrives in pieces and lands as ONE block")
  void text_chunks_are_accumulated_not_emitted_one_by_one() {
    ModelResult result =
        ModelReplies.drain(
            saying(
                new ModelEvent.TextChunk("Hello, "),
                new ModelEvent.TextChunk("world"),
                new ModelEvent.TextChunk("!"),
                new ModelEvent.Stopped(StopReason.END_TURN, COST)),
            event -> {});

    assertThat(answered(result).message().content())
        .containsExactly(new TextBlock("Hello, world!"));
  }

  @Test
  @DisplayName("provider state is kept, whatever it happens to be")
  void provider_state_reaches_the_message() {
    ObjectNode signed = JsonNodeFactory.instance.objectNode();
    signed.put("thinking", "let me think");
    signed.put("signature", "sig-1");

    ModelResult result =
        ModelReplies.drain(
            saying(
                new ModelEvent.ReasoningChunk("let me "),
                new ModelEvent.ReasoningChunk("think"),
                new ModelEvent.ProviderStateEmitted("anthropic", signed),
                new ModelEvent.TextChunk("done"),
                new ModelEvent.Stopped(StopReason.END_TURN, COST)),
            event -> {});

    assertThat(answered(result).message().content())
        .containsExactly(new ProviderBlock("anthropic", signed), new TextBlock("done"));
  }

  @Test
  @DisplayName("reasoning text is narrated and never stored")
  void reasoning_does_not_reach_the_message() {
    List<ModelEvent> watched = new java.util.ArrayList<>();

    ModelResult result =
        ModelReplies.drain(
            saying(
                new ModelEvent.ReasoningChunk("half a thought"),
                new ModelEvent.TextChunk("anyway"),
                new ModelEvent.Stopped(StopReason.END_TURN, COST)),
            watched::add);

    assertThat(answered(result).message().content()).containsExactly(new TextBlock("anyway"));
    assertThat(watched).anyMatch(ModelEvent.ReasoningChunk.class::isInstance);
  }

  @Test
  @DisplayName("prose said on the way to a call is commentary, not an answer")
  void prose_before_a_tool_call_becomes_commentary() {
    ToolCall call = new ToolCall(CallId.of("c1"), "look_up", JsonNodeFactory.instance.objectNode());

    ModelResult result =
        ModelReplies.drain(
            saying(
                new ModelEvent.TextChunk("let me check"),
                new ModelEvent.ToolCallEmitted(call),
                new ModelEvent.Stopped(StopReason.TOOL_USE, COST)),
            event -> {});

    assertThat(asked(result).content())
        .containsExactly(new CommentaryBlock("let me check"), new ToolCallBlock(call));
  }

  @Test
  void a_refusal_is_its_own_outcome_rather_than_an_empty_reply() {
    ModelResult result =
        ModelReplies.drain(saying(new ModelEvent.Refused("cyber", "no", COST)), event -> {});

    assertThat(result).isInstanceOf(ModelResult.Refused.class);
    assertThat(((ModelResult.Refused) result).category()).isEqualTo("cyber");
  }

  @Test
  @DisplayName("a stream that stops saying why still yields what did arrive")
  void a_truncated_stream_keeps_what_it_got() {
    ModelResult result =
        ModelReplies.drain(saying(new ModelEvent.TextChunk("partial")), event -> {});

    assertThat(answered(result).message().content()).containsExactly(new TextBlock("partial"));
  }

  @Test
  void every_event_is_shown_to_the_watcher_in_order() {
    List<ModelEvent> seen = new ArrayList<>();

    ModelReplies.drain(
        saying(
            new ModelEvent.TextChunk("a"),
            new ModelEvent.TextChunk("b"),
            new ModelEvent.Stopped(StopReason.END_TURN, COST)),
        seen::add);

    assertThat(seen).hasSize(3);
    assertThat(seen.getFirst()).isEqualTo(new ModelEvent.TextChunk("a"));
  }

  @Test
  void the_stream_is_closed_even_though_the_caller_never_touches_it() {
    boolean[] closed = {false};
    ModelStream stream =
        new ModelStream() {
          @Override
          public Iterator<ModelEvent> iterator() {
            return List.<ModelEvent>of(new ModelEvent.Stopped(StopReason.END_TURN, COST))
                .iterator();
          }

          @Override
          public void close() {
            closed[0] = true;
          }
        };

    ModelReplies.drain(stream, event -> {});

    assertThat(closed[0]).isTrue();
  }
}
