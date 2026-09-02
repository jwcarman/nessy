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
package org.jwcarman.nessy.model.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.spi.model.ModelEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStart;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlockStart;

/**
 * Every fixture chunk here is assembled through {@link ConverseStreamOutput}'s own static factories
 * ({@code messageStartBuilder()}, {@code contentBlockStartBuilder()}, etc.) rather than each event
 * type's own {@code .builder()} — mirroring {@code GeminiStreamTest}'s builder-fixtures approach,
 * but through the one entry point that actually matters here: the AWS SDK generates two
 * implementations per event type — a base class whose {@code accept(Visitor)} unconditionally
 * throws {@code UnsupportedOperationException} (a stub {@code MessageStopEvent .builder()} would
 * build), and an internal {@code Default*} subclass (returned only by {@link
 * ConverseStreamOutput}'s own builder factories) whose {@code accept(Visitor)} genuinely dispatches
 * to the matching {@code visit*} call — the same subclass the SDK's own wire deserializer
 * constructs for a real stream. {@link BedrockStream} double-dispatches via {@code accept}, so only
 * the {@code Default*}-producing factories are usable as offline fixtures here.
 */
class BedrockStreamTest {

  private static List<ModelEvent> drain(List<ConverseStreamOutput> chunks) {
    var stream = new BedrockStream(chunks, () -> {});
    var collected = new ArrayList<ModelEvent>();
    stream.forEach(collected::add);
    return collected;
  }

  private static ConverseStreamOutput textDelta(int index, String text) {
    return ConverseStreamOutput.contentBlockDeltaBuilder()
        .contentBlockIndex(index)
        .delta(builder -> builder.text(text))
        .build();
  }

  private static ConverseStreamOutput toolUseStart(int index, String id, String name) {
    return ConverseStreamOutput.contentBlockStartBuilder()
        .contentBlockIndex(index)
        .start(
            ContentBlockStart.builder()
                .toolUse(ToolUseBlockStart.builder().toolUseId(id).name(name).build())
                .build())
        .build();
  }

  private static ConverseStreamOutput toolUseInputDelta(int index, String partialJson) {
    return ConverseStreamOutput.contentBlockDeltaBuilder()
        .contentBlockIndex(index)
        .delta(builder -> builder.toolUse(toolUseDelta -> toolUseDelta.input(partialJson)))
        .build();
  }

  private static ConverseStreamOutput contentBlockStop(int index) {
    return ConverseStreamOutput.contentBlockStopBuilder().contentBlockIndex(index).build();
  }

  private static ConverseStreamOutput messageStop(String stopReason) {
    return ConverseStreamOutput.messageStopBuilder().stopReason(stopReason).build();
  }

  private static ConverseStreamOutput metadata(long inputTokens, long outputTokens) {
    return ConverseStreamOutput.metadataBuilder()
        .usage(
            TokenUsage.builder()
                .inputTokens((int) inputTokens)
                .outputTokens((int) outputTokens)
                .build())
        .build();
  }

  private static ConverseStreamOutput metadata(
      long inputTokens, long outputTokens, long cacheReadTokens) {
    return metadata(inputTokens, outputTokens, cacheReadTokens, 0);
  }

  private static ConverseStreamOutput metadata(
      long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
    return ConverseStreamOutput.metadataBuilder()
        .usage(
            TokenUsage.builder()
                .inputTokens((int) inputTokens)
                .outputTokens((int) outputTokens)
                .cacheReadInputTokens((int) cacheReadTokens)
                .cacheWriteInputTokens((int) cacheWriteTokens)
                .build())
        .build();
  }

  @Nested
  class TextTurn {

    @Test
    void text_deltas_become_text_chunks_and_the_turn_ends_with_folded_usage() {
      var chunks =
          List.of(
              textDelta(0, "Hello"),
              textDelta(0, " world"),
              messageStop("end_turn"),
              metadata(10, 5));

      var modelEvents = drain(chunks);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.TextChunk("Hello"),
              new ModelEvent.TextChunk(" world"),
              new ModelEvent.Stopped(StopReason.END_TURN, new Usage(10, 5, 0, 0)));
    }

    @Test
    void usage_carrying_cache_read_tokens_lands_in_turn_ended_usage() {
      var chunks = List.of(textDelta(0, "Hello"), messageStop("end_turn"), metadata(10, 5, 4));

      var modelEvents = drain(chunks);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.TextChunk("Hello"),
              new ModelEvent.Stopped(StopReason.END_TURN, new Usage(14, 5, 4, 0)));
    }

    /**
     * Bedrock's own documentation: "When prompt caching is enabled, the {@code inputTokens} field
     * represents only the non-cached input tokens (tokens that were not read from or written to the
     * cache)", with {@code total input tokens = inputTokens + cacheReadInputTokens +
     * cacheWriteInputTokens}. The OTel GenAI conventions want the total on {@code
     * gen_ai.usage.input_tokens}, with the cache counts as subsets — so the adapter adds them up,
     * exactly as the Anthropic one does.
     */
    @Test
    void input_tokens_are_reported_as_the_semconv_total_of_all_three_components() {
      var chunks =
          List.of(textDelta(0, "Hello"), messageStop("end_turn"), metadata(100, 5, 900, 0));

      var modelEvents = drain(chunks);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.TextChunk("Hello"),
              new ModelEvent.Stopped(StopReason.END_TURN, new Usage(1000, 5, 900, 0)));
    }

    @Test
    void an_empty_text_delta_produces_no_text_chunk() {
      var chunks = List.of(textDelta(0, ""), messageStop("end_turn"));

      var modelEvents = drain(chunks);

      assertThat(modelEvents)
          .containsExactly(new ModelEvent.Stopped(StopReason.END_TURN, Usage.unreported()));
    }
  }

  @Nested
  class ToolUseBlocks {

    @Test
    void a_complete_tool_use_block_emits_once_it_closes() {
      var chunks =
          List.of(
              toolUseStart(0, "call-1", "get_weather"),
              toolUseInputDelta(0, "{\"location\":"),
              toolUseInputDelta(0, "\"NYC\"}"),
              contentBlockStop(0),
              messageStop("tool_use"));

      var modelEvents = drain(chunks);

      assertThat(modelEvents).hasSize(2);
      assertThat(modelEvents.get(0)).isInstanceOf(ModelEvent.ToolCallEmitted.class);
      var toolUseEmitted = (ModelEvent.ToolCallEmitted) modelEvents.get(0);
      assertThat(toolUseEmitted.call().id()).isEqualTo(CallId.of("call-1"));
      assertThat(toolUseEmitted.call().name()).isEqualTo("get_weather");
      assertThat(toolUseEmitted.call().arguments().get("location").asText()).isEqualTo("NYC");
      // Nothing rides on the call any more: a continuity token, if this vendor had one, would be
      // its own provider-state event.
      assertThat(modelEvents).noneMatch(ModelEvent.ProviderStateEmitted.class::isInstance);
    }

    @Test
    void a_tool_use_block_with_no_input_deltas_gets_empty_object_arguments() {
      var chunks =
          List.of(toolUseStart(0, "call-1", "ping"), contentBlockStop(0), messageStop("tool_use"));

      var modelEvents = drain(chunks);

      var call = ((ModelEvent.ToolCallEmitted) modelEvents.get(0)).call();
      assertThat(call.arguments().isObject()).isTrue();
      assertThat(call.arguments().size()).isZero();
    }

    @Test
    void two_tool_use_blocks_at_different_indexes_each_emit_in_order() {
      var chunks =
          List.of(
              toolUseStart(0, "call-1", "get_weather"),
              toolUseStart(1, "call-2", "get_time"),
              toolUseInputDelta(0, "{}"),
              toolUseInputDelta(1, "{}"),
              contentBlockStop(0),
              contentBlockStop(1),
              messageStop("tool_use"));

      var modelEvents = drain(chunks);

      assertThat(modelEvents).hasSize(3);
      assertThat(((ModelEvent.ToolCallEmitted) modelEvents.get(0)).call().id())
          .isEqualTo(CallId.of("call-1"));
      assertThat(((ModelEvent.ToolCallEmitted) modelEvents.get(1)).call().id())
          .isEqualTo(CallId.of("call-2"));
    }

    @Test
    void a_tool_use_turn_reports_tool_use_stop_reason() {
      var chunks =
          List.of(
              toolUseStart(0, "call-1", "get_weather"),
              contentBlockStop(0),
              messageStop("tool_use"));

      var modelEvents = drain(chunks);

      var turnEnded = (ModelEvent.Stopped) modelEvents.get(modelEvents.size() - 1);
      assertThat(turnEnded.reason()).isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    void malformed_accumulated_json_fails_loudly() {
      var chunks =
          List.of(
              toolUseStart(0, "call-1", "get_weather"),
              toolUseInputDelta(0, "not json"),
              contentBlockStop(0),
              messageStop("tool_use"));
      var stream = new BedrockStream(chunks, () -> {});

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("get_weather");
    }
  }

  @Nested
  class StopReasonMapping {

    @Test
    void end_turn_maps_to_end_turn() {
      var modelEvents = drain(List.of(messageStop("end_turn")));
      assertThat(((ModelEvent.Stopped) modelEvents.get(0)).reason()).isEqualTo(StopReason.END_TURN);
    }

    @Test
    void stop_sequence_maps_to_end_turn() {
      var modelEvents = drain(List.of(messageStop("stop_sequence")));
      assertThat(((ModelEvent.Stopped) modelEvents.get(0)).reason()).isEqualTo(StopReason.END_TURN);
    }

    @Test
    void max_tokens_maps_to_max_tokens() {
      var modelEvents = drain(List.of(messageStop("max_tokens")));
      assertThat(((ModelEvent.Stopped) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.MAX_TOKENS);
    }

    @Test
    void model_context_window_exceeded_maps_to_max_tokens() {
      var modelEvents = drain(List.of(messageStop("model_context_window_exceeded")));
      assertThat(((ModelEvent.Stopped) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.MAX_TOKENS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"guardrail_intervened", "content_filtered"})
    void guardrail_and_content_filtered_become_refused_events(String stopReason) {
      var modelEvents = drain(List.of(messageStop(stopReason)));
      // StopReason names only the three ways a turn that HAPPENED can end, so these are their own
      // event — and each keeps the vendor's own reason rather than one flattened category, so a
      // guardrail can still be told apart from a content filter.
      assertThat(modelEvents.get(0)).isInstanceOf(ModelEvent.Refused.class);
      assertThat(((ModelEvent.Refused) modelEvents.get(0)).category()).isEqualTo(stopReason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed_model_output", "malformed_tool_use"})
    void an_unmapped_known_stop_reason_fails_loudly_naming_it(String stopReason) {
      var chunks = List.of(messageStop(stopReason));
      var stream = new BedrockStream(chunks, () -> {});

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(stopReason);
    }

    /**
     * A wire value this SDK build has never heard of collapses to the SDK's own {@code
     * UNKNOWN_TO_SDK_VERSION} sentinel before it ever reaches {@code mapStopReason} — and that
     * sentinel carries no copy of the original string (its {@code toString()} is {@code "null"}),
     * so unlike the known-but-unmapped case above, the original wire value cannot be named in the
     * failure message. Only the fact that stop-reason mapping is what failed can be.
     */
    @Test
    void a_stop_reason_this_sdk_build_does_not_recognize_still_fails_loudly() {
      var chunks = List.of(messageStop("bogus_reason"));
      var stream = new BedrockStream(chunks, () -> {});

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("stopReason");
    }
  }

  @Nested
  class UsageTolerance {

    @Test
    void a_stream_that_never_delivers_a_metadata_event_reports_no_cost_rather_than_no_tokens() {
      var chunks = List.of(textDelta(0, "hi"), messageStop("end_turn"));

      var modelEvents = drain(chunks);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.TextChunk("hi"),
              new ModelEvent.Stopped(StopReason.END_TURN, Usage.unreported()));
    }
  }

  @Nested
  class Closing {

    @Test
    void close_invokes_the_close_callback() {
      var closed = new boolean[1];
      var stream = new BedrockStream(List.of(), () -> closed[0] = true);

      stream.close();

      assertThat(closed[0]).isTrue();
    }
  }

  @Nested
  class LazyTranslation {

    @Test
    void iteration_does_not_pull_more_sdk_chunks_than_necessary() {
      var pulled = new int[1];
      var chunks = List.of(textDelta(0, "one"), textDelta(0, "two"), messageStop("end_turn"));
      Iterable<ConverseStreamOutput> countingChunks =
          () -> {
            var delegate = chunks.iterator();
            return new Iterator<>() {
              @Override
              public boolean hasNext() {
                return delegate.hasNext();
              }

              @Override
              public ConverseStreamOutput next() {
                pulled[0]++;
                return delegate.next();
              }
            };
          };

      var iterator = new BedrockStream(countingChunks, () -> {}).iterator();
      assertThat(iterator.hasNext()).isTrue();
      var first = iterator.next();

      assertThat(first).isEqualTo(new ModelEvent.TextChunk("one"));
      assertThat(pulled[0]).isEqualTo(1);
    }
  }

  @Nested
  class StreamIntegrity {

    @Test
    void a_stream_that_ends_without_ever_seeing_a_message_stop_event_fails_loudly() {
      var chunks = List.of(textDelta(0, "partial"));
      var stream = new BedrockStream(chunks, () -> {});

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("messageStop");
    }

    @Test
    void a_stream_that_ends_with_an_orphaned_tool_use_fragment_fails_loudly() {
      var chunks = List.of(toolUseStart(0, "call-1", "get_weather"), messageStop("tool_use"));
      var stream = new BedrockStream(chunks, () -> {});

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("orphaned");
    }

    @Test
    void calling_next_again_after_exhaustion_throws_no_such_element() {
      var chunks = List.of(messageStop("end_turn"));
      var iterator = new BedrockStream(chunks, () -> {}).iterator();

      while (iterator.hasNext()) {
        iterator.next();
      }

      assertThatThrownBy(iterator::next).isInstanceOf(NoSuchElementException.class);
    }
  }
}
