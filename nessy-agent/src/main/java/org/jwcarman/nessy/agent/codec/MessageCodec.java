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
package org.jwcarman.nessy.agent.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
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
 * Internal storage machinery: renders the {@link Message}/{@link Context} vocabulary to and from
 * the JSON the string-payload storage kernel persists (spec §7). Not API vocabulary — nothing here
 * is meant to be called outside the recipes that back {@code Memory} and friends.
 *
 * <p>Every polymorphic {@link ContentBlock} carries a {@code "type"} discriminator naming the
 * record: {@code text}, {@code image}, {@code thinking}, {@code redacted-thinking}, {@code
 * tool-use}, {@code tool-result} — the same binding convention {@code Schemas}/{@code SealedInputs}
 * use for tool inputs, applied here by hand since the block shapes are fixed and small. Reads are
 * tolerant: unknown fields are ignored, absent optional fields are defaulted. Malformed JSON or an
 * unrecognized discriminator fails loudly with an {@link IllegalArgumentException} naming the
 * offense — domain types carry zero Jackson annotations.
 */
public final class MessageCodec {

  private static final String TYPE = "type";
  private static final String TEXT = "text";
  private static final String SIGNATURE = "signature";
  private static final String CONTENT = "content";

  private static final String TYPE_TEXT = "text";
  private static final String TYPE_IMAGE = "image";
  private static final String TYPE_THINKING = "thinking";
  private static final String TYPE_REDACTED_THINKING = "redacted-thinking";
  private static final String TYPE_TOOL_USE = "tool-use";
  private static final String TYPE_TOOL_RESULT = "tool-result";

  private MessageCodec() {}

  public static String toJson(Message message) {
    Objects.requireNonNull(message, "message must not be null");
    return writeMessage(message).toString();
  }

  public static Message message(String json) {
    Objects.requireNonNull(json, "json must not be null");
    return readMessage(Codecs.readObject(json, "message"));
  }

  /** Package-private seam for {@link StateCodec}: one message node, not a whole JSON string. */
  static ObjectNode writeMessageNode(Message message) {
    return writeMessage(message);
  }

  /** Package-private seam for {@link StateCodec}: one message node, not a whole JSON string. */
  static Message readMessageNode(ObjectNode node) {
    return readMessage(node);
  }

  /** Package-private seam for {@link StateCodec}: one content-block node. */
  static ObjectNode writeBlockNode(ContentBlock block) {
    return writeBlock(block);
  }

  /** Package-private seam for {@link StateCodec}: one content-block node. */
  static ContentBlock readBlockNode(ObjectNode node) {
    return readBlock(node);
  }

  public static String toJson(Context context) {
    Objects.requireNonNull(context, "context must not be null");
    ObjectNode root = Codecs.MAPPER.createObjectNode();
    ArrayNode messages = root.putArray("messages");
    context.messages().forEach(m -> messages.add(writeMessage(m)));
    return root.toString();
  }

  public static Context context(String json) {
    Objects.requireNonNull(json, "json must not be null");
    ObjectNode root = Codecs.readObject(json, "context");
    ArrayNode messagesNode = Codecs.requireArray(root, "messages", "context");
    List<Message> messages = new ArrayList<>();
    for (JsonNode node : messagesNode) {
      messages.add(readMessage(Codecs.requireObject(node, "context message")));
    }
    return Context.of(messages);
  }

  private static ObjectNode writeMessage(Message message) {
    ObjectNode node = Codecs.MAPPER.createObjectNode();
    node.put("role", message.role().name().toLowerCase(Locale.ROOT));
    ArrayNode content = node.putArray(CONTENT);
    message.content().forEach(b -> content.add(writeBlock(b)));
    return node;
  }

  private static Message readMessage(ObjectNode node) {
    String roleText = Codecs.requireText(node, "role", "message");
    Role role = readRole(roleText);
    ArrayNode contentNode = Codecs.requireArray(node, CONTENT, "message");
    List<ContentBlock> content = new ArrayList<>();
    for (JsonNode block : contentNode) {
      content.add(readBlock(Codecs.requireObject(block, "content block")));
    }
    return new Message(role, content);
  }

  private static Role readRole(String roleText) {
    return switch (roleText.toLowerCase(Locale.ROOT)) {
      case "user" -> Role.USER;
      case "assistant" -> Role.ASSISTANT;
      default ->
          throw new IllegalArgumentException(
              "unknown message role: " + roleText + "; expected one of: user, assistant");
    };
  }

  private static ObjectNode writeBlock(ContentBlock block) {
    return switch (block) {
      case TextBlock(String text) ->
          Codecs.MAPPER.createObjectNode().put(TYPE, TYPE_TEXT).put(TEXT, text);
      case ImageBlock(String mediaType, String base64Data) ->
          Codecs.MAPPER
              .createObjectNode()
              .put(TYPE, TYPE_IMAGE)
              .put("mediaType", mediaType)
              .put("base64Data", base64Data);
      case ThinkingBlock(String text, String signature) ->
          Codecs.MAPPER
              .createObjectNode()
              .put(TYPE, TYPE_THINKING)
              .put(TEXT, text)
              .put(SIGNATURE, signature);
      case RedactedThinkingBlock(String data) ->
          Codecs.MAPPER.createObjectNode().put(TYPE, TYPE_REDACTED_THINKING).put("data", data);
      case ToolUseBlock(ToolCall call, String signature) -> writeToolUse(call, signature);
      case ToolResultBlock(String toolUseId, String content, boolean isError) ->
          Codecs.MAPPER
              .createObjectNode()
              .put(TYPE, TYPE_TOOL_RESULT)
              .put("toolUseId", toolUseId)
              .put(CONTENT, content)
              .put("isError", isError);
    };
  }

  private static ObjectNode writeToolUse(ToolCall call, String signature) {
    ObjectNode node =
        Codecs.MAPPER
            .createObjectNode()
            .put(TYPE, TYPE_TOOL_USE)
            .put("id", call.id())
            .put("name", call.name());
    node.set("arguments", call.arguments());
    if (signature != null) {
      node.put(SIGNATURE, signature);
    }
    return node;
  }

  private static ContentBlock readBlock(ObjectNode node) {
    String type = Codecs.requireText(node, TYPE, "content block");
    return switch (type) {
      case TYPE_TEXT -> new TextBlock(Codecs.requireText(node, TEXT, "text block"));
      case TYPE_IMAGE ->
          new ImageBlock(
              Codecs.requireText(node, "mediaType", "image block"),
              Codecs.requireText(node, "base64Data", "image block"));
      case TYPE_THINKING ->
          new ThinkingBlock(
              Codecs.requireText(node, TEXT, "thinking block"), optionalText(node, SIGNATURE, ""));
      case TYPE_REDACTED_THINKING ->
          new RedactedThinkingBlock(Codecs.requireText(node, "data", "redacted-thinking block"));
      case TYPE_TOOL_USE -> readToolUse(node);
      case TYPE_TOOL_RESULT ->
          new ToolResultBlock(
              Codecs.requireText(node, "toolUseId", "tool-result block"),
              Codecs.requireText(node, CONTENT, "tool-result block"),
              requireBoolean(node, "isError", "tool-result block"));
      default ->
          throw new IllegalArgumentException(
              "unknown content block type: "
                  + type
                  + "; expected one of: "
                  + TYPE_TEXT
                  + ", "
                  + TYPE_IMAGE
                  + ", "
                  + TYPE_THINKING
                  + ", "
                  + TYPE_REDACTED_THINKING
                  + ", "
                  + TYPE_TOOL_USE
                  + ", "
                  + TYPE_TOOL_RESULT);
    };
  }

  private static ContentBlock readToolUse(ObjectNode node) {
    ToolCall call =
        new ToolCall(
            Codecs.requireText(node, "id", "tool-use block"),
            Codecs.requireText(node, "name", "tool-use block"),
            Codecs.requireField(node, "arguments", "tool-use block"));
    return new ToolUseBlock(call, optionalText(node, SIGNATURE, null));
  }

  private static String optionalText(ObjectNode node, String name, String fallback) {
    JsonNode field = node.get(name);
    return field == null || field.isNull() ? fallback : field.asText();
  }

  private static boolean requireBoolean(ObjectNode node, String name, String owner) {
    JsonNode field = node.get(name);
    if (field == null || !field.isBoolean()) {
      throw new IllegalArgumentException(owner + " missing required field: " + name);
    }
    return field.asBoolean();
  }
}
