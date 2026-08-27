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
package org.jwcarman.nessy.examples.watchman.pekko;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real model, against LM Studio's OpenAI-compatible endpoint. Free, local, no token spend.
 *
 * <p>Unlike the spike's client this speaks the tool protocol PROPERLY: an assistant turn goes back
 * with its {@code tool_calls} intact and each result goes back as a {@code role: "tool"} message
 * keyed by {@code tool_call_id}. The spike could flatten everything to prose because its transcript
 * was a list of strings; a real port cannot, because the model expects the ids it issued.
 */
public final class LmStudioModel implements WatchmanModel {

  private static final Logger LOG = LoggerFactory.getLogger(LmStudioModel.class);

  private static final String SYSTEM =
      """
      You are the watchman for a single Linux server. Every half hour you do your rounds.

      Use your read-only tools to look at the box: disk_usage and containers. If something needs
      fixing that you cannot fix yourself, propose the tool that would fix it -- prune_images
      removes unused Docker images and REQUIRES a human to approve it, so propose it and do not
      expect it to run during this round. long_job starts a whole-disk trim that takes minutes.

      Call the tools you need, then write one short paragraph of notes about what you found.
      """;

  private final ObjectMapper json = new ObjectMapper();
  private final String baseUrl;
  private final String modelId;
  private final String apiKey;
  private HttpClient http;

  public LmStudioModel(String baseUrl, String modelId, String apiKey) {
    this.baseUrl = baseUrl;
    this.modelId = modelId;
    this.apiKey = apiKey;
  }

  @Override
  public CompletionStage<ModelReply> reply(List<Turn> transcript, Executor blocking) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofMinutes(10))
            .POST(HttpRequest.BodyPublishers.ofString(body(transcript)))
            .build();
    return client(blocking)
        .sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(this::read);
  }

  private String body(List<Turn> transcript) {
    ObjectNode root = json.createObjectNode();
    root.put("model", modelId);
    root.put("temperature", 0.0);
    ArrayNode messages = root.putArray("messages");
    messages.addObject().put("role", "system").put("content", SYSTEM);

    for (Turn turn : transcript) {
      switch (turn) {
        case Turn.User(String text) ->
            messages.addObject().put("role", "user").put("content", text);
        case Turn.Assistant(String text, var calls) -> {
          ObjectNode assistant = messages.addObject();
          assistant.put("role", "assistant");
          assistant.put("content", text == null ? "" : text);
          if (!calls.isEmpty()) {
            ArrayNode toolCalls = assistant.putArray("tool_calls");
            for (Turn.ToolRequest call : calls) {
              ObjectNode entry = toolCalls.addObject();
              entry.put("id", call.id());
              entry.put("type", "function");
              ObjectNode function = entry.putObject("function");
              function.put("name", call.tool());
              function.put("arguments", call.argumentsJson());
            }
          }
        }
        case Turn.ToolResult(String callId, String tool, String text) -> {
          ObjectNode result = messages.addObject();
          result.put("role", "tool");
          result.put("tool_call_id", callId);
          result.put("name", tool);
          result.put("content", text);
        }
      }
    }
    root.set("tools", WatchmanTools.schemas());
    return root.toString();
  }

  private ModelReply read(HttpResponse<String> response) {
    if (response.statusCode() != 200) {
      return new ModelReply.Failed(
          "LM Studio said " + response.statusCode() + ": " + response.body());
    }
    JsonNode choice;
    try {
      choice = json.readTree(response.body()).path("choices").path(0);
    } catch (IOException e) {
      return new ModelReply.Failed("unreadable LM Studio response: " + e.getMessage());
    }
    JsonNode message = choice.path("message");
    String content = message.path("content").asText("").strip();
    JsonNode toolCalls = message.path("tool_calls");
    LOG.info("[watchman] model finish_reason={}", choice.path("finish_reason").asText());

    if (!toolCalls.isArray() || toolCalls.isEmpty()) {
      return new ModelReply.Said(content);
    }
    List<Turn.ToolRequest> requests = new ArrayList<>();
    for (JsonNode call : toolCalls) {
      JsonNode function = call.path("function");
      requests.add(
          new Turn.ToolRequest(
              call.path("id").asText("call-" + requests.size()),
              function.path("name").asText(),
              function.path("arguments").asText("{}")));
    }
    LOG.info(
        "[watchman] model asked for {}", requests.stream().map(Turn.ToolRequest::tool).toList());
    return new ModelReply.AskedForTools(content, requests);
  }

  private synchronized HttpClient client(Executor blocking) {
    if (http == null) {
      http =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).executor(blocking).build();
    }
    return http;
  }
}
