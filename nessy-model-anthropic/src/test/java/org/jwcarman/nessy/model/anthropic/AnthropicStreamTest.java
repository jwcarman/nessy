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
package org.jwcarman.nessy.model.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.InputJsonDelta;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawContentBlockStopEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.SignatureDelta;
import com.anthropic.models.messages.TextDelta;
import com.anthropic.models.messages.ThinkingDelta;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.ModelEvent;

class AnthropicStreamTest {

  private static List<ModelEvent> drain(List<RawMessageStreamEvent> events) {
    var stream = new AnthropicStream(fakeStream(events, () -> {}));
    var collected = new ArrayList<ModelEvent>();
    stream.forEach(collected::add);
    return collected;
  }

  private static StreamResponse<RawMessageStreamEvent> fakeStream(
      List<RawMessageStreamEvent> events, Runnable onClose) {
    return new StreamResponse<>() {
      @Override
      public Stream<RawMessageStreamEvent> stream() {
        return events.stream();
      }

      @Override
      public void close() {
        onClose.run();
      }
    };
  }

  private static RawMessageStreamEvent parseEvent(String json) {
    try {
      return ObjectMappers.jsonMapper().readValue(json, RawMessageStreamEvent.class);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static RawMessageStreamEvent messageStart(long inputTokens) {
    return messageStart(inputTokens, 0);
  }

  private static RawMessageStreamEvent messageStart(long inputTokens, long cacheReadInputTokens) {
    return parseEvent(
        """
        {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",
        "content":[],"model":"claude-test","stop_reason":null,"stop_sequence":null,
        "usage":{"input_tokens":%d,"output_tokens":1,"cache_read_input_tokens":%d}}}
        """
            .formatted(inputTokens, cacheReadInputTokens));
  }

  private static RawMessageStreamEvent messageDelta(String stopReason, long outputTokens) {
    return parseEvent(
        """
        {"type":"message_delta","delta":{"stop_reason":"%s","stop_sequence":null},
        "usage":{"output_tokens":%d}}
        """
            .formatted(stopReason, outputTokens));
  }

  private static RawMessageStreamEvent messageStop() {
    return parseEvent(
        """
        {"type":"message_stop"}
        """);
  }

  private static RawMessageStreamEvent textBlockStart(long index) {
    return parseEvent(
        """
        {"type":"content_block_start","index":%d,"content_block":{"type":"text","text":""}}
        """
            .formatted(index));
  }

  private static RawMessageStreamEvent thinkingBlockStart(long index) {
    return parseEvent(
        """
        {"type":"content_block_start","index":%d,
        "content_block":{"type":"thinking","thinking":"","signature":""}}
        """
            .formatted(index));
  }

  private static RawMessageStreamEvent textDelta(long index, String text) {
    return RawMessageStreamEvent.ofContentBlockDelta(
        RawContentBlockDeltaEvent.builder()
            .index(index)
            .delta(RawContentBlockDelta.ofText(TextDelta.builder().text(text).build()))
            .build());
  }

  private static RawMessageStreamEvent thinkingDelta(long index, String thinking) {
    return RawMessageStreamEvent.ofContentBlockDelta(
        RawContentBlockDeltaEvent.builder()
            .index(index)
            .delta(
                RawContentBlockDelta.ofThinking(ThinkingDelta.builder().thinking(thinking).build()))
            .build());
  }

  private static RawMessageStreamEvent signatureDelta(long index, String signature) {
    return RawMessageStreamEvent.ofContentBlockDelta(
        RawContentBlockDeltaEvent.builder()
            .index(index)
            .delta(
                RawContentBlockDelta.ofSignature(
                    SignatureDelta.builder().signature(signature).build()))
            .build());
  }

  private static RawMessageStreamEvent inputJsonDelta(long index, String partialJson) {
    return RawMessageStreamEvent.ofContentBlockDelta(
        RawContentBlockDeltaEvent.builder()
            .index(index)
            .delta(
                RawContentBlockDelta.ofInputJson(
                    InputJsonDelta.builder().partialJson(partialJson).build()))
            .build());
  }

  private static RawMessageStreamEvent toolUseStart(long index, String id, String name) {
    return RawMessageStreamEvent.ofContentBlockStart(
        RawContentBlockStartEvent.builder()
            .index(index)
            .contentBlock(
                ToolUseBlock.builder()
                    .id(id)
                    .name(name)
                    .input(JsonValue.from(Map.of()))
                    .caller(DirectCaller.builder().build())
                    .build())
            .build());
  }

  private static RawMessageStreamEvent redactedThinkingStart(long index, String data) {
    return RawMessageStreamEvent.ofContentBlockStart(
        RawContentBlockStartEvent.builder()
            .index(index)
            .redactedThinkingContentBlock(data)
            .build());
  }

  private static RawMessageStreamEvent contentBlockStop(long index) {
    return RawMessageStreamEvent.ofContentBlockStop(
        RawContentBlockStopEvent.builder().index(index).build());
  }

  @Nested
  class TextTurn {

    @Test
    void text_deltas_become_text_chunks_and_the_turn_ends_with_combined_usage() {
      var events =
          List.of(
              messageStart(10),
              textBlockStart(0),
              textDelta(0, "Hello"),
              textDelta(0, " world"),
              contentBlockStop(0),
              messageDelta("end_turn", 5),
              messageStop());

      var modelEvents = drain(events);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.TextChunk("Hello"),
              new ModelEvent.TextChunk(" world"),
              new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(10, 5, 0)));
    }
  }

  @Nested
  class CachedTokens {

    @Test
    void a_message_start_carrying_cache_read_input_tokens_lands_in_turn_ended_usage() {
      var events =
          List.of(
              messageStart(10, 7),
              textBlockStart(0),
              textDelta(0, "Hello"),
              contentBlockStop(0),
              messageDelta("end_turn", 5),
              messageStop());

      var modelEvents = drain(events);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.TextChunk("Hello"),
              new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(10, 5, 7)));
    }

    @Test
    void a_message_start_without_cache_read_input_tokens_yields_zero() {
      var events =
          List.of(
              messageStart(10),
              textBlockStart(0),
              textDelta(0, "Hello"),
              contentBlockStop(0),
              messageDelta("end_turn", 5),
              messageStop());

      var modelEvents = drain(events);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.TextChunk("Hello"),
              new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(10, 5, 0)));
    }
  }

  @Nested
  class ThinkingTurn {

    @Test
    void thinking_and_signature_deltas_become_chunk_then_signed_events() {
      var events =
          List.of(
              messageStart(20),
              thinkingBlockStart(0),
              thinkingDelta(0, "Let me think"),
              signatureDelta(0, "sig-123"),
              contentBlockStop(0),
              messageDelta("end_turn", 8),
              messageStop());

      var modelEvents = drain(events);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.ThinkingChunk("Let me think"),
              new ModelEvent.ThinkingSigned("sig-123"),
              new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(20, 8, 0)));
    }
  }

  @Nested
  class RedactedThinking {

    @Test
    void a_redacted_thinking_block_becomes_one_emitted_event_at_start() {
      var events =
          List.of(
              messageStart(6),
              redactedThinkingStart(0, "opaque-data"),
              contentBlockStop(0),
              messageDelta("end_turn", 2),
              messageStop());

      var modelEvents = drain(events);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.RedactedThinkingEmitted("opaque-data"),
              new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(6, 2, 0)));
    }
  }

  @Nested
  class ToolUseAssembly {

    @Test
    void two_tools_with_fragmented_input_json_each_assemble_into_one_tool_use_emitted() {
      var events =
          List.of(
              messageStart(15),
              toolUseStart(0, "toolu_1", "get_weather"),
              inputJsonDelta(0, "{\"loc"),
              inputJsonDelta(0, "ation\":\"NYC\"}"),
              contentBlockStop(0),
              toolUseStart(1, "toolu_2", "get_time"),
              inputJsonDelta(1, "{\"zone"),
              inputJsonDelta(1, "\":\"EST\"}"),
              contentBlockStop(1),
              messageDelta("tool_use", 12),
              messageStop());

      var modelEvents = drain(events);

      assertThat(modelEvents).hasSize(3);
      assertThat(modelEvents.get(0)).isInstanceOf(ModelEvent.ToolUseEmitted.class);
      var firstCall = ((ModelEvent.ToolUseEmitted) modelEvents.get(0)).call();
      assertThat(firstCall.id()).isEqualTo("toolu_1");
      assertThat(firstCall.name()).isEqualTo("get_weather");
      assertThat(firstCall.arguments().get("location").asText()).isEqualTo("NYC");

      assertThat(modelEvents.get(1)).isInstanceOf(ModelEvent.ToolUseEmitted.class);
      var secondCall = ((ModelEvent.ToolUseEmitted) modelEvents.get(1)).call();
      assertThat(secondCall.id()).isEqualTo("toolu_2");
      assertThat(secondCall.name()).isEqualTo("get_time");
      assertThat(secondCall.arguments().get("zone").asText()).isEqualTo("EST");

      assertThat(modelEvents.get(2))
          .isEqualTo(new ModelEvent.TurnEnded(StopReason.TOOL_USE, new Usage(15, 12, 0)));
    }

    @Test
    void a_tool_with_no_input_json_deltas_at_all_gets_empty_object_arguments() {
      var events =
          List.of(
              messageStart(5),
              toolUseStart(0, "toolu_3", "ping"),
              contentBlockStop(0),
              messageDelta("tool_use", 3),
              messageStop());

      var modelEvents = drain(events);

      assertThat(modelEvents.get(0)).isInstanceOf(ModelEvent.ToolUseEmitted.class);
      var call = ((ModelEvent.ToolUseEmitted) modelEvents.get(0)).call();
      assertThat(call)
          .isEqualTo(new ToolCall("toolu_3", "ping", JsonNodeFactory.instance.objectNode()));
      assertThat(call.arguments().size()).isZero();
    }

    @Test
    void a_tool_with_one_empty_input_json_delta_also_gets_empty_object_arguments() {
      // The wire shape a real zero-arg tool actually sends: one input_json_delta whose
      // partial_json is the empty string, not simply no deltas at all.
      var events =
          List.of(
              messageStart(5),
              toolUseStart(0, "toolu_4", "ping"),
              inputJsonDelta(0, ""),
              contentBlockStop(0),
              messageDelta("tool_use", 3),
              messageStop());

      var modelEvents = drain(events);

      var call = ((ModelEvent.ToolUseEmitted) modelEvents.get(0)).call();
      assertThat(call)
          .isEqualTo(new ToolCall("toolu_4", "ping", JsonNodeFactory.instance.objectNode()));
    }

    @Test
    void truncated_json_from_a_stream_cut_off_mid_tool_use_fails_loudly_with_diagnosis() {
      var events =
          List.of(
              messageStart(5),
              toolUseStart(0, "toolu_5", "get_weather"),
              inputJsonDelta(0, "{\"le"),
              contentBlockStop(0),
              messageDelta("max_tokens", 3),
              messageStop());

      var stream = new AnthropicStream(fakeStream(events, () -> {}));

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("get_weather")
          .hasMessageContaining("toolu_5")
          .hasMessageContaining("{\"le");
    }

    @Test
    void arguments_that_parse_to_something_other_than_a_json_object_fail_loudly() {
      var events =
          List.of(
              messageStart(5),
              toolUseStart(0, "toolu_6", "get_weather"),
              inputJsonDelta(0, "null"),
              contentBlockStop(0),
              messageDelta("tool_use", 3),
              messageStop());

      var stream = new AnthropicStream(fakeStream(events, () -> {}));

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("get_weather")
          .hasMessageContaining("toolu_6")
          .hasMessageContaining("null");
    }
  }

  @Nested
  class UsageArithmetic {

    @Test
    void input_tokens_from_message_start_combine_with_output_tokens_from_message_delta() {
      var events = List.of(messageStart(37), messageDelta("end_turn", 9), messageStop());

      var modelEvents = drain(events);

      assertThat(modelEvents)
          .containsExactly(new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(37, 9, 0)));
    }
  }

  @Nested
  class StopReasonMapping {

    @Test
    void end_turn_maps_to_end_turn() {
      var modelEvents = drain(List.of(messageStart(1), messageDelta("end_turn", 1), messageStop()));
      assertThat(((ModelEvent.TurnEnded) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.END_TURN);
    }

    @Test
    void tool_use_maps_to_tool_use() {
      var modelEvents = drain(List.of(messageStart(1), messageDelta("tool_use", 1), messageStop()));
      assertThat(((ModelEvent.TurnEnded) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    void max_tokens_maps_to_max_tokens() {
      var modelEvents =
          drain(List.of(messageStart(1), messageDelta("max_tokens", 1), messageStop()));
      assertThat(((ModelEvent.TurnEnded) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.MAX_TOKENS);
    }

    @Test
    void stop_sequence_maps_to_end_turn() {
      var modelEvents =
          drain(List.of(messageStart(1), messageDelta("stop_sequence", 1), messageStop()));
      assertThat(((ModelEvent.TurnEnded) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.END_TURN);
    }

    @Test
    void refusal_maps_to_refusal() {
      var modelEvents = drain(List.of(messageStart(1), messageDelta("refusal", 1), messageStop()));
      assertThat(((ModelEvent.TurnEnded) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.REFUSAL);
    }

    @Test
    void model_context_window_exceeded_maps_to_max_tokens() {
      // "ran out of room" either way: max_tokens is the output budget,
      // model_context_window_exceeded is the context budget. The reducer already halts cleanly on
      // MAX_TOKENS, so both fold into the same StopReason rather than needing a new grammar
      // variant.
      var modelEvents =
          drain(
              List.of(
                  messageStart(1),
                  messageDelta("model_context_window_exceeded", 1),
                  messageStop()));
      assertThat(((ModelEvent.TurnEnded) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.MAX_TOKENS);
    }

    @Test
    void a_stop_reason_the_sdk_itself_knows_but_we_do_not_map_fails_loudly_naming_it() {
      var events = List.of(messageStart(1), messageDelta("pause_turn", 1), messageStop());
      var stream = new AnthropicStream(fakeStream(events, () -> {}));

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("pause_turn");
    }

    @Test
    void a_stop_reason_the_sdk_has_never_seen_also_fails_loudly_naming_it() {
      // Pins that mapStopReason() dispatches on the wire string itself (reason.asString()) rather
      // than the SDK's own Known/Value enum: a value the SDK has genuinely never heard of must
      // still land in our IllegalStateException, not the SDK's own exception type.
      var events = List.of(messageStart(1), messageDelta("some_future_reason", 1), messageStop());
      var stream = new AnthropicStream(fakeStream(events, () -> {}));

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("some_future_reason");
    }
  }

  @Nested
  class Closing {

    @Test
    void close_closes_the_underlying_sdk_stream() {
      var closed = new boolean[1];
      var stream = new AnthropicStream(fakeStream(List.of(), () -> closed[0] = true));

      stream.close();

      assertThat(closed[0]).isTrue();
    }
  }

  @Nested
  class LazyTranslation {

    @Test
    void iteration_does_not_pull_more_sdk_events_than_necessary() {
      var pulled = new int[1];
      var events =
          List.of(
              messageStart(1),
              textDelta(0, "one"),
              textDelta(0, "two"),
              contentBlockStop(0),
              messageDelta("end_turn", 1),
              messageStop());
      var countingStream =
          new StreamResponse<RawMessageStreamEvent>() {
            @Override
            public Stream<RawMessageStreamEvent> stream() {
              return events.stream().peek(e -> pulled[0]++);
            }

            @Override
            public void close() {}
          };

      var iterator = new AnthropicStream(countingStream).iterator();
      assertThat(iterator.hasNext()).isTrue();
      var first = iterator.next();

      assertThat(first).isEqualTo(new ModelEvent.TextChunk("one"));
      assertThat(pulled[0]).isLessThan(events.size());
    }
  }

  @Nested
  class StreamIntegrity {

    @Test
    void a_stream_that_ends_without_ever_seeing_a_message_delta_fails_loudly() {
      var events = List.of(messageStart(1), textDelta(0, "partial"));
      var stream = new AnthropicStream(fakeStream(events, () -> {}));

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("message_delta");
    }

    /**
     * Mirrors {@code OpenAiStream}'s exhaustion guard: a {@code content_block_start} for a {@code
     * tool_use} block that never gets its matching {@code content_block_stop} leaves an orphaned
     * entry in {@code toolUsesByIndex}. Silently dropping it on stream exhaustion would lose part
     * of a tool call the caller never finds out about, so this fails loudly instead, naming the
     * index.
     */
    @Test
    void a_stream_that_ends_with_an_orphaned_tool_use_fails_loudly_naming_the_index() {
      var events =
          List.of(
              messageStart(1),
              toolUseStart(0, "toolu_1", "get_weather"),
              inputJsonDelta(0, "{\"loc"),
              messageDelta("max_tokens", 1)
              // no content_block_stop for index 0, no message_stop: the stream just ends here.
              );
      var stream = new AnthropicStream(fakeStream(events, () -> {}));

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("orphaned")
          .hasMessageContaining("0");
    }
  }
}
