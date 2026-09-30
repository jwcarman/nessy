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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.blocking.ResponseService;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Responses server in a box: finished responses written as the JSON the API sends, cut into the
 * events it would have streamed them as, behind a fake {@link OpenAIClient}.
 *
 * <p>The client is a JDK dynamic proxy -- not a mocking library, just {@link
 * Proxy#newProxyInstance} -- answering {@code responses().createStreaming(...)} and throwing {@link
 * UnsupportedOperationException} for everything else. A proxy intercepts every interface method
 * itself, default ones included, so it matches on the method name alone.
 */
final class ResponseStreams {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ResponseStreams() {}

  static OpenAIClient client(Function<ResponseCreateParams, List<ResponseStreamEvent>> answer) {
    ResponseService responses =
        (ResponseService)
            Proxy.newProxyInstance(
                ResponseService.class.getClassLoader(),
                new Class<?>[] {ResponseService.class},
                (proxy, method, args) -> {
                  if ("createStreaming".equals(method.getName())) {
                    List<ResponseStreamEvent> events = answer.apply((ResponseCreateParams) args[0]);
                    return new StreamResponse<ResponseStreamEvent>() {
                      @Override
                      public Stream<ResponseStreamEvent> stream() {
                        return events.stream();
                      }

                      @Override
                      public void close() {
                        // Nothing held open.
                      }
                    };
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    return (OpenAIClient)
        Proxy.newProxyInstance(
            OpenAIClient.class.getClassLoader(),
            new Class<?>[] {OpenAIClient.class},
            (proxy, method, args) -> {
              if ("responses".equals(method.getName())) {
                return responses;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  /** A client that streams {@code response} cut into events, whatever it is asked. */
  static OpenAIClient replying(Map<String, Object> response) {
    return client(params -> eventsOf(response));
  }

  // ---- JSON, as the API writes it ----------------------------------------------------------

  static Map<String, Object> fields(Object... pairs) {
    Map<String, Object> fields = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      fields.put((String) pairs[i], pairs[i + 1]);
    }
    return fields;
  }

  static Map<String, Object> message(String id, String text) {
    return fields(
        "type",
        "message",
        "id",
        id,
        "role",
        "assistant",
        "status",
        "completed",
        "content",
        List.of(fields("type", "output_text", "text", text, "annotations", List.of())));
  }

  static Map<String, Object> refusal(String text) {
    return fields(
        "type",
        "message",
        "id",
        "msg_r",
        "role",
        "assistant",
        "status",
        "completed",
        "content",
        List.of(fields("type", "refusal", "refusal", text)));
  }

  static Map<String, Object> functionCall(String callId, String name, String arguments) {
    return fields(
        "type",
        "function_call",
        "id",
        "fc_" + callId,
        "call_id",
        callId,
        "name",
        name,
        "arguments",
        arguments,
        "status",
        "completed");
  }

  static Map<String, Object> reasoning(String id, String encrypted, String summary) {
    Map<String, Object> item =
        fields(
            "type",
            "reasoning",
            "id",
            id,
            "summary",
            List.of(fields("type", "summary_text", "text", summary)));
    if (encrypted != null) {
      item.put("encrypted_content", encrypted);
    }
    return item;
  }

  static Map<String, Object> webSearchCall() {
    return fields(
        "type",
        "web_search_call",
        "id",
        "ws_1",
        "status",
        "completed",
        "action",
        fields("type", "search", "query", "loch ness"));
  }

  static Map<String, Object> usage(long input, long output) {
    return fields("input_tokens", input, "output_tokens", output, "total_tokens", input + output);
  }

  /** A finished response; {@code usage} may be null, and then the key is absent. */
  static Map<String, Object> response(
      String status, List<Map<String, Object>> output, Map<String, Object> usage) {
    Map<String, Object> response =
        fields(
            "id",
            "resp_1",
            "object",
            "response",
            "created_at",
            0,
            "model",
            "gpt-4o",
            "status",
            status,
            "output",
            output,
            "parallel_tool_calls",
            true,
            "tool_choice",
            "auto",
            "tools",
            List.of(),
            "error",
            null,
            "incomplete_details",
            null,
            "instructions",
            null,
            "metadata",
            Map.of(),
            "temperature",
            1,
            "top_p",
            1);
    if (usage != null) {
      response.put("usage", usage);
    }
    return response;
  }

  static Map<String, Object> completed(List<Map<String, Object>> output) {
    return response("completed", output, null);
  }

  // ---- the stream ------------------------------------------------------------------------

  /**
   * {@code response} cut into what a server streams: {@code response.created}, then per output item
   * an {@code output_item.added} and its text (or arguments) five characters at a time, and last
   * the terminal event named for the status, carrying the whole response.
   */
  static List<ResponseStreamEvent> eventsOf(Map<String, Object> response) {
    List<ResponseStreamEvent> events = new ArrayList<>();
    int seq = 0;
    Map<String, Object> started = new LinkedHashMap<>(response);
    started.put("status", "in_progress");
    started.put("output", List.of());
    events.add(
        event(fields("type", "response.created", "sequence_number", seq++, "response", started)));
    List<?> output = (List<?>) response.get("output");
    for (int index = 0; index < output.size(); index++) {
      Map<?, ?> item = (Map<?, ?>) output.get(index);
      events.add(
          event(
              fields(
                  "type",
                  "response.output_item.added",
                  "sequence_number",
                  seq++,
                  "output_index",
                  index,
                  "item",
                  item)));
      if ("message".equals(item.get("type"))) {
        for (Object part : (List<?>) item.get("content")) {
          Map<?, ?> content = (Map<?, ?>) part;
          if ("output_text".equals(content.get("type"))) {
            for (String piece : pieces((String) content.get("text"))) {
              events.add(textDelta(seq++, String.valueOf(item.get("id")), index, piece));
            }
          }
        }
      } else if ("function_call".equals(item.get("type"))) {
        for (String piece : pieces((String) item.get("arguments"))) {
          events.add(
              event(
                  fields(
                      "type",
                      "response.function_call_arguments.delta",
                      "sequence_number",
                      seq++,
                      "item_id",
                      item.get("id"),
                      "output_index",
                      index,
                      "delta",
                      piece)));
        }
      }
    }
    events.add(
        event(
            fields(
                "type",
                "response." + response.get("status"),
                "sequence_number",
                seq,
                "response",
                response)));
    return events;
  }

  static ResponseStreamEvent textDelta(int seq, String itemId, int index, String delta) {
    return event(
        fields(
            "type",
            "response.output_text.delta",
            "sequence_number",
            seq,
            "item_id",
            itemId,
            "output_index",
            index,
            "content_index",
            0,
            "delta",
            delta,
            "logprobs",
            List.of()));
  }

  /** Any event, from the JSON a server would send. */
  static ResponseStreamEvent event(Map<String, Object> fields) {
    try {
      return ObjectMappers.jsonMapper()
          .readValue(JSON.writeValueAsString(fields), ResponseStreamEvent.class);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Five characters at a time, so a stream of them is several events. */
  static List<String> pieces(String text) {
    List<String> pieces = new ArrayList<>();
    for (int i = 0; i < text.length(); i += 5) {
      pieces.add(text.substring(i, Math.min(text.length(), i + 5)));
    }
    return pieces;
  }
}
