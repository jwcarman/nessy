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
package org.jwcarman.nessy.spike.pekko;

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
 * THROWAWAY SPIKE. Real inference, against LM Studio's OpenAI-compatible endpoint.
 *
 * <p><b>Why a hand-written client rather than {@code nessy-model-openai}.</b> Two reasons, both
 * about keeping the spike honest rather than about the module being unsuitable:
 *
 * <ol>
 *   <li>Its API speaks Nessy's domain — {@code Message}, {@code ToolSpec}, {@code ToolCall}. Using
 *       it would drag the very {@code nessy-agent}/{@code nessy-api} vocabulary back into a spike
 *       whose whole point is to see what shape Pekko wants when nothing is inherited.
 *   <li>On Pekko 1.x this module was pinned to Jackson 2.21.6, so pulling a reactor module in would
 *       have put two Jackson 2 lines on one classpath. That reason has EXPIRED under Pekko 2.0 (see
 *       the POM), but the first reason has not, so the client stays.
 * </ol>
 *
 * <p>It has, however, acquired a second job. This class deliberately uses {@code
 * com.fasterxml.jackson.databind} — Jackson <b>2</b>.22.0, the reactor's own pin — while Pekko
 * serialises every durable state with {@code tools.jackson.databind}, Jackson <b>3</b>. Both are on
 * this classpath and both are exercised in the same JVM by the live demo. That coexistence is the
 * headline finding of the 2.0 round, and this client is what proves it at runtime rather than
 * merely in a dependency tree.
 *
 * <p>So: about eighty lines of {@code java.net.http}, no domain crossover, no dependency tangle.
 *
 * <p>The conversation it sends is a flattening of the spike's {@code List<String>} transcript, not
 * the strict OpenAI tool protocol: tool results go back as plain user turns rather than as {@code
 * role: "tool"} messages keyed by {@code tool_call_id}. That is a consequence of the spike's
 * deliberately thin domain, and it is worth naming — a real integration would carry the ids.
 */
public final class LmStudioSpikeModel implements SpikeModel {

  private static final Logger LOG = LoggerFactory.getLogger(LmStudioSpikeModel.class);

  public static final String BASE_URL = "http://localhost:1234/v1";
  public static final String MODEL_ID = "qwen/qwen3.6-35b-a3b";

  private static final String SYSTEM =
      """
      You are a small tidying agent with exactly two tools.
      Use the clock tool to read the time, and the delete tool to remove a file.
      When the user asks you to tidy up, call BOTH tools in one turn: clock, and delete on \
      /tmp/everything. Once you have both tool results, reply with one short sentence and \
      call no further tools.
      """;

  private final ObjectMapper json = new ObjectMapper();
  private HttpClient http;
  private final String baseUrl;
  private final String modelId;
  private final String apiKey;

  public LmStudioSpikeModel(String baseUrl, String modelId, String apiKey) {
    this.baseUrl = baseUrl;
    this.modelId = modelId;
    this.apiKey = apiKey;
  }

  @Override
  public CompletionStage<SpikeModelReply> reply(List<String> transcript, Executor blocking) {
    // HttpClient does its own I/O, but every callback and body handler runs on OUR executor,
    // so nothing this class causes ever lands on a Pekko dispatcher.
    HttpClient client = client(blocking);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofMinutes(5))
            .POST(HttpRequest.BodyPublishers.ofString(body(transcript)))
            .build();
    return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(this::read);
  }

  private String body(List<String> transcript) {
    ObjectNode root = json.createObjectNode();
    root.put("model", modelId);
    root.put("temperature", 0.0);

    ArrayNode messages = root.putArray("messages");
    messages.addObject().put("role", "system").put("content", SYSTEM);
    for (String line : transcript) {
      if (line.startsWith("assistant: ")) {
        messages.addObject().put("role", "assistant").put("content", line.substring(11));
      } else if (line.startsWith("tool: ")) {
        messages
            .addObject()
            .put("role", "user")
            .put("content", "tool result -- " + line.substring(6));
      } else if (line.startsWith("user: ")) {
        messages.addObject().put("role", "user").put("content", line.substring(6));
      }
    }

    ArrayNode tools = root.putArray("tools");
    tools.add(tool("clock", "Read the current time.", null));
    tools.add(tool("delete", "Delete a file.", "path"));
    return root.toString();
  }

  private ObjectNode tool(String name, String description, String argument) {
    ObjectNode tool = json.createObjectNode();
    tool.put("type", "function");
    ObjectNode function = tool.putObject("function");
    function.put("name", name);
    function.put("description", description);
    ObjectNode parameters = function.putObject("parameters");
    parameters.put("type", "object");
    ObjectNode properties = parameters.putObject("properties");
    if (argument != null) {
      properties.putObject(argument).put("type", "string").put("description", "the " + argument);
      parameters.putArray("required").add(argument);
    }
    return tool;
  }

  private SpikeModelReply read(HttpResponse<String> response) {
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "LM Studio said " + response.statusCode() + ": " + response.body());
    }
    JsonNode choice;
    try {
      choice = json.readTree(response.body()).path("choices").path(0);
    } catch (IOException e) {
      throw new IllegalStateException("unreadable LM Studio response", e);
    }
    JsonNode message = choice.path("message");
    LOG.info("[spike] LM Studio finish_reason={}", choice.path("finish_reason").asText());

    JsonNode toolCalls = message.path("tool_calls");
    if (!toolCalls.isArray() || toolCalls.isEmpty()) {
      return new SpikeModelReply.Said(message.path("content").asText("").strip());
    }
    List<SpikeModelReply.Request> requests = new ArrayList<>();
    for (JsonNode call : toolCalls) {
      JsonNode function = call.path("function");
      requests.add(
          new SpikeModelReply.Request(
              call.path("id").asText("call-" + requests.size()),
              function.path("name").asText(),
              argumentOf(function.path("arguments").asText("{}"))));
    }
    LOG.info("[spike] LM Studio asked for {}", requests);
    return new SpikeModelReply.AskedForTools(requests);
  }

  /** The spike's tools take one string; take the first textual value the model supplied. */
  private String argumentOf(String arguments) {
    try {
      JsonNode node = json.readTree(arguments);
      for (JsonNode value : node) {
        if (value.isTextual()) {
          return value.asText();
        }
      }
    } catch (IOException e) {
      LOG.warn("[spike] unreadable tool arguments {}", arguments, e);
    }
    return arguments;
  }

  private synchronized HttpClient client(Executor blocking) {
    if (http == null) {
      http =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).executor(blocking).build();
    }
    return http;
  }

  @Override
  public synchronized void close() {
    if (http != null) {
      http.close();
    }
  }
}
