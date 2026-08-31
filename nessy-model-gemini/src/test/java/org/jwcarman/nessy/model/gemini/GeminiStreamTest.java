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
package org.jwcarman.nessy.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.spi.model.ModelEvent;

/**
 * Every fixture chunk here is assembled with the SDK's own builders (mirroring {@code
 * OpenAiStreamTest}'s approach): {@code GenerateContentResponse}, {@code Candidate}, {@code Part},
 * and friends are all plain builder-constructed POJOs, unlike {@code ResponseStream} itself (see
 * {@link GeminiClient}'s class javadoc for why that one resists offline construction).
 */
class GeminiStreamTest {

  private static List<ModelEvent> drain(List<GenerateContentResponse> chunks) {
    var stream = new GeminiStream(chunks, () -> {});
    var collected = new ArrayList<ModelEvent>();
    stream.forEach(collected::add);
    return collected;
  }

  private static GenerateContentResponse textChunk(String text) {
    return GenerateContentResponse.builder()
        .candidates(
            List.of(
                Candidate.builder()
                    .content(
                        Content.builder().role("model").parts(List.of(Part.fromText(text))).build())
                    .build()))
        .build();
  }

  private static GenerateContentResponse functionCallChunk(
      String id, String name, Map<String, Object> args) {
    var functionCallPart =
        Part.builder()
            .functionCall(FunctionCall.builder().id(id).name(name).args(args).build())
            .build();
    return GenerateContentResponse.builder()
        .candidates(
            List.of(
                Candidate.builder()
                    .content(
                        Content.builder().role("model").parts(List.of(functionCallPart)).build())
                    .build()))
        .build();
  }

  private static GenerateContentResponse functionCallChunkWithSignature(
      String id, String name, Map<String, Object> args, byte[] thoughtSignature) {
    var functionCallPart =
        Part.builder()
            .functionCall(FunctionCall.builder().id(id).name(name).args(args).build())
            .thoughtSignature(thoughtSignature)
            .build();
    return GenerateContentResponse.builder()
        .candidates(
            List.of(
                Candidate.builder()
                    .content(
                        Content.builder().role("model").parts(List.of(functionCallPart)).build())
                    .build()))
        .build();
  }

  private static GenerateContentResponse functionCallChunkWithoutId(
      String name, Map<String, Object> args) {
    var functionCallPart =
        Part.builder().functionCall(FunctionCall.builder().name(name).args(args).build()).build();
    return GenerateContentResponse.builder()
        .candidates(
            List.of(
                Candidate.builder()
                    .content(
                        Content.builder().role("model").parts(List.of(functionCallPart)).build())
                    .build()))
        .build();
  }

  private static GenerateContentResponse finishChunk(String finishReason) {
    return GenerateContentResponse.builder()
        .candidates(
            List.of(Candidate.builder().finishReason(new FinishReason(finishReason)).build()))
        .build();
  }

  private static GenerateContentResponse usageChunk(long promptTokens, long candidatesTokens) {
    return GenerateContentResponse.builder()
        .usageMetadata(
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount((int) promptTokens)
                .candidatesTokenCount((int) candidatesTokens)
                .build())
        .build();
  }

  private static GenerateContentResponse usageChunk(
      long promptTokens, long candidatesTokens, long cachedTokens) {
    return GenerateContentResponse.builder()
        .usageMetadata(
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount((int) promptTokens)
                .candidatesTokenCount((int) candidatesTokens)
                .cachedContentTokenCount((int) cachedTokens)
                .build())
        .build();
  }

  private static GenerateContentResponse thoughtChunk(String text) {
    var thoughtPart = Part.builder().text(text).thought(true).build();
    return GenerateContentResponse.builder()
        .candidates(
            List.of(
                Candidate.builder()
                    .content(Content.builder().role("model").parts(List.of(thoughtPart)).build())
                    .build()))
        .build();
  }

  @Nested
  class TextTurn {

    @Test
    void text_parts_become_text_chunks_and_the_turn_ends_with_folded_usage() {
      var chunks =
          List.of(textChunk("Hello"), textChunk(" world"), finishChunk("STOP"), usageChunk(10, 5));

      var modelEvents = drain(chunks);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.TextChunk("Hello"),
              new ModelEvent.TextChunk(" world"),
              new ModelEvent.Stopped(StopReason.END_TURN, new Usage(10, 5)));
    }

    @Test
    void usage_carrying_cached_prompt_tokens_lands_in_turn_ended_usage() {
      var chunks = List.of(textChunk("Hello"), finishChunk("STOP"), usageChunk(10, 5, 4));

      var modelEvents = drain(chunks);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.TextChunk("Hello"),
              new ModelEvent.Stopped(StopReason.END_TURN, new Usage(10, 5, 4, 0)));
    }

    @Test
    void a_later_usage_chunk_s_running_total_overwrites_an_earlier_one() {
      var chunks =
          List.of(
              textChunk("Hello"),
              usageChunk(4, 1),
              textChunk(" world"),
              usageChunk(10, 5),
              finishChunk("STOP"));

      var modelEvents = drain(chunks);

      assertThat(((ModelEvent.Stopped) modelEvents.get(modelEvents.size() - 1)).usage())
          .isEqualTo(new Usage(10, 5));
    }

    @Test
    void an_empty_text_part_produces_no_text_chunk() {
      var chunks = List.of(textChunk(""), finishChunk("STOP"));

      var modelEvents = drain(chunks);

      assertThat(modelEvents)
          .containsExactly(new ModelEvent.Stopped(StopReason.END_TURN, new Usage(0, 0)));
    }
  }

  @Nested
  class ThoughtPartsAreDropped {

    @Test
    void a_thought_flagged_part_produces_no_event() {
      var chunks =
          List.of(thoughtChunk("reasoning..."), textChunk("the answer"), finishChunk("STOP"));

      var modelEvents = drain(chunks);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.TextChunk("the answer"),
              new ModelEvent.Stopped(StopReason.END_TURN, new Usage(0, 0)));
    }
  }

  @Nested
  class FunctionCalls {

    @Test
    void a_complete_function_call_part_emits_immediately_with_its_own_id() {
      var chunks =
          List.of(
              functionCallChunk("call-1", "get_weather", Map.of("location", "NYC")),
              finishChunk("STOP"));

      var modelEvents = drain(chunks);

      assertThat(modelEvents).hasSize(2);
      assertThat(modelEvents.get(0)).isInstanceOf(ModelEvent.ToolCallEmitted.class);
      var call = ((ModelEvent.ToolCallEmitted) modelEvents.get(0)).call();
      assertThat(call.id()).isEqualTo("call-1");
      assertThat(call.name()).isEqualTo("get_weather");
      assertThat(call.arguments().get("location").asText()).isEqualTo("NYC");
    }

    @Test
    void a_function_call_turn_reports_tool_use_even_though_finish_reason_is_stop() {
      var chunks =
          List.of(functionCallChunk("call-1", "get_weather", Map.of()), finishChunk("STOP"));

      var modelEvents = drain(chunks);

      var turnEnded = (ModelEvent.Stopped) modelEvents.get(modelEvents.size() - 1);
      assertThat(turnEnded.reason()).isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    void a_function_call_missing_an_id_gets_a_deterministic_minted_id() {
      var chunks =
          List.of(
              functionCallChunkWithoutId("first", Map.of()),
              functionCallChunkWithoutId("second", Map.of()),
              finishChunk("STOP"));

      var modelEvents = drain(chunks);

      var firstCall = ((ModelEvent.ToolCallEmitted) modelEvents.get(0)).call();
      var secondCall = ((ModelEvent.ToolCallEmitted) modelEvents.get(1)).call();
      assertThat(firstCall.id()).isEqualTo("gemini-call-0");
      assertThat(secondCall.id()).isEqualTo("gemini-call-1");
    }

    @Test
    void a_function_call_with_no_args_gets_empty_object_arguments() {
      var chunks = List.of(functionCallChunk("call-1", "ping", Map.of()), finishChunk("STOP"));

      var modelEvents = drain(chunks);

      var call = ((ModelEvent.ToolCallEmitted) modelEvents.get(0)).call();
      assertThat(call.arguments().isObject()).isTrue();
      assertThat(call.arguments().size()).isZero();
    }

    @Test
    void multiple_function_calls_in_one_turn_each_emit_in_order() {
      var multiCallChunk =
          GenerateContentResponse.builder()
              .candidates(
                  List.of(
                      Candidate.builder()
                          .content(
                              Content.builder()
                                  .role("model")
                                  .parts(
                                      List.of(
                                          Part.builder()
                                              .functionCall(
                                                  FunctionCall.builder()
                                                      .id("call-1")
                                                      .name("get_weather")
                                                      .args(Map.of())
                                                      .build())
                                              .build(),
                                          Part.builder()
                                              .functionCall(
                                                  FunctionCall.builder()
                                                      .id("call-2")
                                                      .name("get_time")
                                                      .args(Map.of())
                                                      .build())
                                              .build()))
                                  .build())
                          .build()))
              .build();

      var modelEvents = drain(List.of(multiCallChunk, finishChunk("STOP")));

      assertThat(modelEvents).hasSize(3);
      assertThat(((ModelEvent.ToolCallEmitted) modelEvents.get(0)).call().id()).isEqualTo("call-1");
      assertThat(((ModelEvent.ToolCallEmitted) modelEvents.get(1)).call().id()).isEqualTo("call-2");
    }

    @Test
    void a_function_call_with_no_name_fails_loudly() {
      var noNameChunk =
          GenerateContentResponse.builder()
              .candidates(
                  List.of(
                      Candidate.builder()
                          .content(
                              Content.builder()
                                  .role("model")
                                  .parts(
                                      List.of(
                                          Part.builder()
                                              .functionCall(
                                                  FunctionCall.builder()
                                                      .id("call-1")
                                                      .args(Map.of())
                                                      .build())
                                              .build()))
                                  .build())
                          .build()))
              .build();

      var stream = new GeminiStream(List.of(noNameChunk, finishChunk("STOP")), () -> {});

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no name");
    }
  }

  @Nested
  class ThoughtSignatures {

    @Test
    void
        a_function_call_part_carrying_a_thought_signature_emits_an_event_with_the_encoded_signature() {
      byte[] rawSignature = "opaque-continuity-token".getBytes(StandardCharsets.UTF_8);
      var chunks =
          List.of(
              functionCallChunkWithSignature(
                  "call-1", "get_weather", Map.of("location", "NYC"), rawSignature),
              finishChunk("STOP"));

      var modelEvents = drain(chunks);

      var toolUseEmitted = (ModelEvent.ToolCallEmitted) modelEvents.get(0);
      assertThat(toolUseEmitted.signature())
          .isEqualTo(Base64.getEncoder().encodeToString(rawSignature));
    }

    @Test
    void a_function_call_part_with_no_thought_signature_emits_an_event_with_no_signature() {
      var chunks =
          List.of(
              functionCallChunk("call-1", "get_weather", Map.of("location", "NYC")),
              finishChunk("STOP"));

      var modelEvents = drain(chunks);

      var toolUseEmitted = (ModelEvent.ToolCallEmitted) modelEvents.get(0);
      assertThat(toolUseEmitted.signature()).isNull();
    }
  }

  @Nested
  class FinishReasonMapping {

    @Test
    void stop_maps_to_end_turn() {
      var modelEvents = drain(List.of(finishChunk("STOP")));
      assertThat(((ModelEvent.Stopped) modelEvents.get(0)).reason()).isEqualTo(StopReason.END_TURN);
    }

    @Test
    void max_tokens_maps_to_max_tokens() {
      var modelEvents = drain(List.of(finishChunk("MAX_TOKENS")));
      assertThat(((ModelEvent.Stopped) modelEvents.get(0)).reason())
          .isEqualTo(StopReason.MAX_TOKENS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SAFETY", "RECITATION", "PROHIBITED_CONTENT"})
    void safety_recitation_and_prohibited_content_all_become_refused_events(String finishReason) {
      var modelEvents = drain(List.of(finishChunk(finishReason)));
      // StopReason names only the three ways a turn that HAPPENED can end, so these are their own
      // event — and each carries the vendor's own reason rather than one flattened category.
      assertThat(modelEvents.get(0)).isInstanceOf(ModelEvent.Refused.class);
      assertThat(((ModelEvent.Refused) modelEvents.get(0)).category()).isEqualTo(finishReason);
    }

    @Test
    void an_unmapped_finish_reason_fails_loudly_naming_it() {
      var chunks = List.of(finishChunk("MALFORMED_FUNCTION_CALL"));
      var stream = new GeminiStream(chunks, () -> {});

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("MALFORMED_FUNCTION_CALL");
    }
  }

  @Nested
  class UsageTolerance {

    @Test
    void a_stream_that_never_delivers_a_usage_chunk_tolerates_and_yields_zero_usage() {
      var chunks = List.of(textChunk("hi"), finishChunk("STOP"));

      var modelEvents = drain(chunks);

      assertThat(modelEvents)
          .containsExactly(
              new ModelEvent.TextChunk("hi"),
              new ModelEvent.Stopped(StopReason.END_TURN, new Usage(0, 0)));
    }
  }

  @Nested
  class Closing {

    @Test
    void close_invokes_the_close_callback() {
      var closed = new boolean[1];
      var stream = new GeminiStream(List.of(), () -> closed[0] = true);

      stream.close();

      assertThat(closed[0]).isTrue();
    }
  }

  @Nested
  class LazyTranslation {

    @Test
    void iteration_does_not_pull_more_sdk_chunks_than_necessary() {
      var pulled = new int[1];
      var chunks = List.of(textChunk("one"), textChunk("two"), finishChunk("STOP"));
      Iterable<GenerateContentResponse> countingChunks =
          () -> {
            var delegate = chunks.iterator();
            return new Iterator<>() {
              @Override
              public boolean hasNext() {
                return delegate.hasNext();
              }

              @Override
              public GenerateContentResponse next() {
                pulled[0]++;
                return delegate.next();
              }
            };
          };

      var iterator = new GeminiStream(countingChunks, () -> {}).iterator();
      assertThat(iterator.hasNext()).isTrue();
      var first = iterator.next();

      assertThat(first).isEqualTo(new ModelEvent.TextChunk("one"));
      assertThat(pulled[0]).isEqualTo(1);
    }
  }

  @Nested
  class StreamIntegrity {

    @Test
    void a_stream_that_ends_without_ever_seeing_a_finish_reason_fails_loudly() {
      var chunks = List.of(textChunk("partial"));
      var stream = new GeminiStream(chunks, () -> {});

      assertThatThrownBy(() -> stream.forEach(event -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("finishReason");
    }

    @Test
    void calling_next_again_after_exhaustion_throws_no_such_element() {
      var chunks = List.of(finishChunk("STOP"));
      var iterator = new GeminiStream(chunks, () -> {}).iterator();

      while (iterator.hasNext()) {
        iterator.next();
      }

      assertThatThrownBy(iterator::next).isInstanceOf(NoSuchElementException.class);
    }
  }
}
