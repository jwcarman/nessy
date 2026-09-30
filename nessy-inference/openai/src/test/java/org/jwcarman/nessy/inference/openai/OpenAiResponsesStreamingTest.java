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
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.completed;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.event;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.eventsOf;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.fields;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.functionCall;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.message;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.textDelta;

import com.openai.models.responses.ResponseStreamEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * What is narrated as a Responses stream arrives, and what a server that bends the protocol costs.
 */
class OpenAiResponsesStreamingTest {

  private final Narration narrated = new Narration();

  private InferenceResult inferNarrating(List<ResponseStreamEvent> events) {
    return OpenAiResponsesInferenceProviderTest.provider(ResponseStreams.client(params -> events))
        .infer(OpenAiResponsesInferenceProviderTest.REQUEST, narrated);
  }

  private static ResponseStreamEvent summaryDelta(int seq, String delta) {
    return event(
        fields(
            "type",
            "response.reasoning_summary_text.delta",
            "sequence_number",
            seq,
            "item_id",
            "rs_1",
            "output_index",
            0,
            "summary_index",
            0,
            "delta",
            delta));
  }

  private static ResponseStreamEvent reasoningTextDelta(int seq, String delta) {
    return event(
        fields(
            "type",
            "response.reasoning_text.delta",
            "sequence_number",
            seq,
            "item_id",
            "rs_1",
            "output_index",
            0,
            "content_index",
            0,
            "delta",
            delta));
  }

  @Nested
  class WhatIsNarrated {

    @Test
    void the_text_is_narrated_piece_by_piece_as_it_arrives_and_answered_whole() {
      InferenceResult result =
          inferNarrating(eventsOf(completed(List.of(message("msg_1", "a lake monster")))));

      assertThat(narrated.fragments())
          .extracting(Narration.Fragment::text)
          .containsExactly("a lak", "e mon", "ster");
      assertThat(result)
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("a lake monster"))));
    }

    @Test
    void a_reasoning_summary_is_narrated_as_thinking_before_the_text() {
      List<ResponseStreamEvent> events = new ArrayList<>();
      events.add(summaryDelta(1, "weighing it"));
      events.addAll(eventsOf(completed(List.of(message("msg_1", "ok")))));

      inferNarrating(events);

      assertThat(narrated.fragments())
          .containsExactly(
              Narration.Fragment.thinking("weighing it"), Narration.Fragment.text("ok"));
    }

    /**
     * What a compatible Responses server may stream unasked, as compatible chat servers send
     * reasoning_content.
     */
    @Test
    void raw_reasoning_text_is_narrated_as_thinking() {
      List<ResponseStreamEvent> events = new ArrayList<>();
      events.add(reasoningTextDelta(1, "hmm"));
      events.addAll(eventsOf(completed(List.of(message("msg_1", "ok")))));

      inferNarrating(events);

      assertThat(narrated.fragments())
          .containsExactly(Narration.Fragment.thinking("hmm"), Narration.Fragment.text("ok"));
    }

    @Test
    void empty_deltas_are_not_narrated() {
      List<ResponseStreamEvent> events = new ArrayList<>();
      events.add(textDelta(1, "msg_1", 0, ""));
      events.add(summaryDelta(2, ""));
      events.addAll(eventsOf(completed(List.of(message("msg_1", "ok")))));

      inferNarrating(events);

      assertThat(narrated.fragments()).containsExactly(Narration.Fragment.text("ok"));
    }

    /** Half a JSON argument is not something anybody can watch. */
    @Test
    void function_call_argument_fragments_are_not_narrated() {
      InferenceResult result =
          inferNarrating(
              eventsOf(
                  completed(
                      List.of(functionCall("call_1", "days_until", "{\"date\":\"2026-12-25\"}")))));

      assertThat(narrated.fragments()).isEmpty();
      assertThat(result).isInstanceOf(InferenceResult.Actions.class);
    }
  }

  @Nested
  class DeviatingServers {

    @Test
    void events_after_the_terminal_one_change_nothing_and_are_not_narrated() {
      List<ResponseStreamEvent> events =
          new ArrayList<>(eventsOf(completed(List.of(message("msg_1", "ok")))));
      events.add(textDelta(99, "msg_2", 1, "trailing"));
      events.add(event(fields("type", "response.rate_limits.updated", "sequence_number", 100)));

      InferenceResult result = inferNarrating(events);

      assertThat(narrated.fragments()).containsExactly(Narration.Fragment.text("ok"));
      assertThat(result)
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("ok"))));
    }

    @Test
    void an_unknown_event_type_is_ignored() {
      List<ResponseStreamEvent> events = new ArrayList<>();
      events.add(event(fields("type", "response.something_new", "sequence_number", 1, "x", 1)));
      events.addAll(eventsOf(completed(List.of(message("msg_1", "ok")))));

      assertThat(inferNarrating(events)).isInstanceOf(InferenceResult.Answer.class);
    }

    @Test
    void a_mid_stream_error_with_no_terminal_event_is_a_fault_naming_the_error() {
      List<ResponseStreamEvent> events =
          List.of(
              textDelta(1, "msg_1", 0, "Par"),
              event(
                  fields(
                      "type",
                      "error",
                      "sequence_number",
                      2,
                      "code",
                      "server_error",
                      "message",
                      "the model fell over",
                      "param",
                      null)));

      InferenceResult result = inferNarrating(events);

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> {
                assertThat(fault.failure()).isInstanceOf(Failure.Permanent.class);
                assertThat(fault.failure().reason())
                    .contains("ended before")
                    .contains("the model fell over");
              });
    }
  }
}
