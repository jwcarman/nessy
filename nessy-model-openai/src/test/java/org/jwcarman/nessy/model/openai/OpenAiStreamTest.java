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
package org.jwcarman.nessy.model.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.completions.CompletionUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.ToolCall;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.spi.model.ModelEvent;

/**
 * Every fixture chunk here is assembled with the SDK's own {@code ChatCompletionChunk} builders
 * (mirroring {@code OpenAiRequestsTest}'s approach), not JSON — the SDK gives no {@code
 * ObjectMappers}-through-JSON shortcut for this type that's any less verbose than the builders
 * themselves.
 */
class OpenAiStreamTest {

  private static final String CHUNK_ID = "chatcmpl_test";
  private static final String MODEL = "gpt-test";

  private static List<ModelEvent> drain(List<ChatCompletionChunk> chunks) {
    var stream = new OpenAiStream(fakeStream(chunks, () -> {}));
    var collected = new ArrayList<ModelEvent>();
    stream.forEach(collected::add);
    return collected;
  }

  private static StreamResponse<ChatCompletionChunk> fakeStream(
      List<ChatCompletionChunk> chunks, Runnable onClose) {
    return new StreamResponse<>() {
      @Override
      public Stream<ChatCompletionChunk> stream() {
        return chunks.stream();
      }

      @Override
      public void close() {
        onClose.run();
      }
    };
  }

  private static ChatCompletionChunk.Builder chunkBuilder() {
    return ChatCompletionChunk.builder().id(CHUNK_ID).created(0L).model(MODEL);
  }

  private static ChatCompletionChunk.Choice.Builder choiceBuilder(long index) {
    return ChatCompletionChunk.Choice.builder()
        .index(index)
        .finishReason(Optional.empty())
        .delta(ChatCompletionChunk.Choice.Delta.builder().build());
  }

  private static ChatCompletionChunk textChunk(String text) {
    return chunkBuilder()
        .addChoice(
            choiceBuilder(0)
                .delta(ChatCompletionChunk.Choice.Delta.builder().content(text).build())
                .build())
        .build();
  }

  /** The fragment that opens a tool-call index: carries the id and function name. */
  private static ChatCompletionChunk toolCallStart(long index, String id, String name) {
    return chunkBuilder()
        .addChoice(
            choiceBuilder(0)
                .delta(
                    ChatCompletionChunk.Choice.Delta.builder()
                        .addToolCall(
                            ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                                .index(index)
                                .id(id)
                                .function(
                                    ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                                        .name(name)
                                        .build())
                                .build())
                        .build())
                .build())
        .build();
  }

  /** A later fragment for an already-opened tool-call index: appends only to arguments. */
  private static ChatCompletionChunk toolCallArguments(long index, String argumentsFragment) {
    return chunkBuilder()
        .addChoice(
            choiceBuilder(0)
                .delta(
                    ChatCompletionChunk.Choice.Delta.builder()
                        .addToolCall(
                            ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                                .index(index)
                                .function(
                                    ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                                        .arguments(argumentsFragment)
                                        .build())
                                .build())
                        .build())
                .build())
        .build();
  }

  private static ChatCompletionChunk finishChunk(String finishReason) {
    return chunkBuilder()
        .addChoice(
            choiceBuilder(0)
                .finishReason(ChatCompletionChunk.Choice.FinishReason.of(finishReason))
                .build())
        .build();
  }

  private static ChatCompletionChunk usageChunk(long promptTokens, long completionTokens) {
    return chunkBuilder()
        .choices(List.of())
        .usage(
            CompletionUsage.builder()
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens)
                .build())
        .build();
  }

  @Nested
  class TextTurn {

    @Test
    void content_deltas_become_text_chunks_and_the_turn_ends_with_folded_usage() {
      var chunks =
          List.of(textChunk("Hello"), textChunk(" world"), finishChunk("stop"), usageChunk(10, 5));

      var modelEvents = drain(chunks);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.TextChunk("Hello"),
              new ModelEvent.TextChunk(" world"),
              new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(10, 5)));
    }
  }

  @Nested
  class ToolCallAssembly {

    @Test
    void two_tool_calls_with_interleaved_fragments_each_assemble_in_index_order() {
      var chunks =
          List.of(
              toolCallStart(0, "call_1", "get_weather"),
              toolCallStart(1, "call_2", "get_time"),
              toolCallArguments(0, "{\"loc"),
              toolCallArguments(1, "{\"zone"),
              toolCallArguments(0, "ation\":\"NYC\"}"),
              toolCallArguments(1, "\":\"EST\"}"),
              finishChunk("tool_calls"),
              usageChunk(20, 12));

      var modelEvents = drain(chunks);

      assertThat(modelEvents).hasSize(3);
      assertThat(modelEvents.get(0)).isInstanceOf(ModelEvent.ToolUseEmitted.class);
      var firstCall = ((ModelEvent.ToolUseEmitted) modelEvents.get(0)).call();
      assertThat(firstCall.id()).isEqualTo("call_1");
      assertThat(firstCall.name()).isEqualTo("get_weather");
      assertThat(firstCall.arguments().get("location").asText()).isEqualTo("NYC");

      assertThat(modelEvents.get(1)).isInstanceOf(ModelEvent.ToolUseEmitted.class);
      var secondCall = ((ModelEvent.ToolUseEmitted) modelEvents.get(1)).call();
      assertThat(secondCall.id()).isEqualTo("call_2");
      assertThat(secondCall.name()).isEqualTo("get_time");
      assertThat(secondCall.arguments().get("zone").asText()).isEqualTo("EST");

      assertThat(modelEvents.get(2))
          .isEqualTo(new ModelEvent.TurnEnded(StopReason.TOOL_USE, new Usage(20, 12)));
    }

    @Test
    void a_tool_call_whose_arguments_fragment_is_the_empty_string_gets_empty_object_arguments() {
      var chunks =
          List.of(
              toolCallStart(0, "call_3", "ping"),
              toolCallArguments(0, ""),
              finishChunk("tool_calls"));

      var modelEvents = drain(chunks);

      var call = ((ModelEvent.ToolUseEmitted) modelEvents.get(0)).call();
      assertThat(call.arguments().isObject()).isTrue();
      assertThat(call.arguments().size()).isZero();
    }

    @Test
    void a_tool_call_with_no_arguments_fragments_at_all_gets_empty_object_arguments() {
      var chunks = List.of(toolCallStart(0, "call_4", "ping"), finishChunk("tool_calls"));

      var modelEvents = drain(chunks);

      var call = ((ModelEvent.ToolUseEmitted) modelEvents.get(0)).call();
      assertThat(call).isEqualTo(new ToolCall("call_4", "ping", call.arguments()));
      assertThat(call.arguments().isObject()).isTrue();
      assertThat(call.arguments().size()).isZero();
    }

    @Test
    void truncated_json_from_a_stream_cut_off_mid_call_fails_loudly_with_diagnosis() {
      var chunks =
          List.of(
              toolCallStart(0, "call_5", "get_weather"),
              toolCallArguments(0, "{\"le"),
              finishChunk("length"));

      var stream = new OpenAiStream(fakeStream(chunks, () -> {}));

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("get_weather")
          .hasMessageContaining("call_5")
          .hasMessageContaining("{\"le");
    }

    @Test
    void arguments_that_parse_to_something_other_than_a_json_object_fail_loudly() {
      var chunks =
          List.of(
              toolCallStart(0, "call_6", "get_weather"),
              toolCallArguments(0, "null"),
              finishChunk("tool_calls"));

      var stream = new OpenAiStream(fakeStream(chunks, () -> {}));

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("get_weather")
          .hasMessageContaining("call_6")
          .hasMessageContaining("null");
    }
  }

  @Nested
  class UsageTolerance {

    @Test
    void a_stream_that_never_delivers_a_usage_chunk_tolerates_and_yields_zero_usage() {
      var chunks = List.of(textChunk("hi"), finishChunk("stop"));

      var modelEvents = drain(chunks);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.TextChunk("hi"),
              new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
    }
  }

  @Nested
  class FinishReasonMapping {

    @Test
    void stop_maps_to_end_turn() {
      var modelEvents = drain(List.of(finishChunk("stop")));
      assertThat(((ModelEvent.TurnEnded) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.END_TURN);
    }

    @Test
    void length_maps_to_max_tokens() {
      var modelEvents = drain(List.of(finishChunk("length")));
      assertThat(((ModelEvent.TurnEnded) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.MAX_TOKENS);
    }

    @Test
    void tool_calls_maps_to_tool_use() {
      var modelEvents = drain(List.of(finishChunk("tool_calls")));
      assertThat(((ModelEvent.TurnEnded) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    void content_filter_maps_to_refusal() {
      var modelEvents = drain(List.of(finishChunk("content_filter")));
      assertThat(((ModelEvent.TurnEnded) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.REFUSAL);
    }

    @Test
    void the_deprecated_function_call_reason_maps_to_tool_use() {
      var modelEvents = drain(List.of(finishChunk("function_call")));
      assertThat(((ModelEvent.TurnEnded) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    void an_unrecognized_finish_reason_fails_loudly_naming_it() {
      var chunks = List.of(finishChunk("some_future_reason"));
      var stream = new OpenAiStream(fakeStream(chunks, () -> {}));

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
      var stream = new OpenAiStream(fakeStream(List.of(), () -> closed[0] = true));

      stream.close();

      assertThat(closed[0]).isTrue();
    }
  }

  @Nested
  class LazyTranslation {

    @Test
    void iteration_does_not_pull_more_sdk_chunks_than_necessary() {
      var pulled = new int[1];
      var chunks = List.of(textChunk("one"), textChunk("two"), finishChunk("stop"));
      var countingStream =
          new StreamResponse<ChatCompletionChunk>() {
            @Override
            public Stream<ChatCompletionChunk> stream() {
              return chunks.stream().peek(c -> pulled[0]++);
            }

            @Override
            public void close() {}
          };

      var iterator = new OpenAiStream(countingStream).iterator();
      assertThat(iterator.hasNext()).isTrue();
      var first = iterator.next();

      assertThat(first).isEqualTo(new ModelEvent.TextChunk("one"));
      assertThat(pulled[0]).isLessThan(chunks.size());
    }
  }

  @Nested
  class StreamIntegrity {

    @Test
    void a_stream_that_ends_without_ever_seeing_a_finish_reason_fails_loudly() {
      var chunks = List.of(textChunk("partial"));
      var stream = new OpenAiStream(fakeStream(chunks, () -> {}));

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("finish_reason");
    }
  }
}
