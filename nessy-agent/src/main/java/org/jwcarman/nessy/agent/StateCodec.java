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
package org.jwcarman.nessy.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.ImageBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * JSON with a type discriminator on the phase (spec §2.3, ruled 2026-08-20). The wire shape is
 * private to this codec; core types carry no serialization annotations. Unknown discriminators fail
 * loudly — a newer node wrote a phase this node does not know.
 */
public final class StateCodec {

  private static final String PHASE = "phase";
  private static final String IDLE = "IDLE";
  private static final String AWAITING_MODEL = "AWAITING_MODEL";
  private static final String AWAITING_TOOLS = "AWAITING_TOOLS";

  private final ObjectMapper mapper = new ObjectMapper();

  public String encode(State state) {
    Objects.requireNonNull(state, "state must not be null");
    ObjectNode root = mapper.createObjectNode();
    root.put("version", state.version());
    switch (state.phase()) {
      case Phase.Idle ignored -> root.put(PHASE, IDLE);
      case Phase.AwaitingModel ignored -> root.put(PHASE, AWAITING_MODEL);
      case Phase.AwaitingTools(
              Message turn,
              Set<String> pending,
              List<ToolResultBlock> gathered) -> {
        root.put(PHASE, AWAITING_TOOLS);
        root.set("assistantTurn", writeMessage(turn));
        ArrayNode pendingNode = root.putArray("pending");
        pending.stream().sorted().forEach(pendingNode::add);
        ArrayNode gatheredNode = root.putArray("gathered");
        gathered.forEach(g -> gatheredNode.add(writeResultBlock(g)));
      }
    }
    return root.toString();
  }

  public State decode(String json) {
    Objects.requireNonNull(json, "json must not be null");
    try {
      JsonNode root = mapper.readTree(json);
      long version = root.get("version").asLong();
      String discriminator = root.get(PHASE).asText();
      Phase phase =
          switch (discriminator) {
            case IDLE -> new Phase.Idle();
            case AWAITING_MODEL -> new Phase.AwaitingModel();
            case AWAITING_TOOLS -> readAwaitingTools(root);
            default ->
                throw new IllegalArgumentException("unknown phase discriminator: " + discriminator);
          };
      return new State(phase, version);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("unreadable state payload", e);
    }
  }

  private Phase readAwaitingTools(JsonNode root) {
    Message turn = readMessage(root.get("assistantTurn"));
    Set<String> pending = new HashSet<>();
    root.get("pending").forEach(n -> pending.add(n.asText()));
    List<ToolResultBlock> gathered = new ArrayList<>();
    root.get("gathered").forEach(n -> gathered.add(readResultBlock(n)));
    return new Phase.AwaitingTools(turn, pending, gathered);
  }

  private ObjectNode writeMessage(Message message) {
    ObjectNode node = mapper.createObjectNode();
    node.put("role", message.role().name());
    ArrayNode content = node.putArray("content");
    message.content().forEach(b -> content.add(writeBlock(b)));
    return node;
  }

  private Message readMessage(JsonNode node) {
    List<ContentBlock> content = new ArrayList<>();
    node.get("content").forEach(b -> content.add(readBlock(b)));
    return new Message(Role.valueOf(node.get("role").asText()), content);
  }

  private ObjectNode writeBlock(ContentBlock block) {
    ObjectNode node = mapper.createObjectNode();
    switch (block) {
      case TextBlock(String text) -> node.put("type", "text").put("text", text);
      case ThinkingBlock t -> {
        node.put("type", "thinking").put("text", t.text());
        if (t.signature() != null) {
          node.put("signature", t.signature());
        }
      }
      case RedactedThinkingBlock(String data) ->
          node.put("type", "redacted_thinking").put("data", data);
      case ToolUseBlock(ToolCall call, String signature) -> {
        node.put("type", "tool_use").put("id", call.id()).put("name", call.name());
        node.set("arguments", call.arguments());
        if (signature != null) {
          node.put("signature", signature);
        }
      }
      case ToolResultBlock b -> node.set("block", writeResultBlock(b));
      case ImageBlock(String mediaType, String base64Data) ->
          node.put("type", "image").put("mediaType", mediaType).put("data", base64Data);
    }
    return node;
  }

  private ContentBlock readBlock(JsonNode node) {
    if (node.has("block")) {
      return readResultBlock(node.get("block"));
    }
    String type = node.get("type").asText();
    return switch (type) {
      case "text" -> new TextBlock(node.get("text").asText());
      case "thinking" ->
          new ThinkingBlock(
              node.get("text").asText(),
              node.has("signature") ? node.get("signature").asText() : null);
      case "redacted_thinking" -> new RedactedThinkingBlock(node.get("data").asText());
      case "tool_use" ->
          new ToolUseBlock(
              new ToolCall(
                  node.get("id").asText(), node.get("name").asText(), node.get("arguments")),
              node.has("signature") ? node.get("signature").asText() : null);
      case "image" ->
          new ImageBlock(node.get("mediaType").asText(), node.get("data").asText()); // data ↔
      // base64Data
      default -> throw new IllegalArgumentException("unknown content block type: " + type);
    };
  }

  private ObjectNode writeResultBlock(ToolResultBlock block) {
    ObjectNode node = mapper.createObjectNode();
    node.put("type", "tool_result")
        .put("toolUseId", block.toolUseId())
        .put("content", block.content())
        .put("isError", block.isError());
    return node;
  }

  private ToolResultBlock readResultBlock(JsonNode node) {
    return new ToolResultBlock(
        node.get("toolUseId").asText(),
        node.get("content").asText(),
        node.get("isError").asBoolean());
  }
}
