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
package org.jwcarman.nessy.store.jdbc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.conversation.AgendaItem;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.ImageBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Jackson (de)serialization for the shapes {@link JdbcConversationStore} and {@link JdbcMemory}
 * persist as Postgres {@code jsonb}: the {@link ConversationState} control block, one {@link
 * AgendaItem} per agenda row, the bare {@link ToolCall} a park row remembers, and one {@link
 * Message} per {@code nessy_memory} row.
 *
 * <p>Every sealed hierarchy this codec crosses — {@link ContentBlock}, {@link ToolResolution},
 * {@link Decision}, {@link AgendaItem} — is not itself annotated; annotating {@code nessy-core}'s
 * API types for one storage backend's wire format would leak a JDBC concern into the core module.
 * Instead each gets a private mixin here, registered with {@link ObjectMapper#addMixIn} on a {@link
 * ObjectMapper#copy() copy} of the mapper the caller hands in — the caller's own mapper, however it
 * is used elsewhere in the harness, is never mutated by constructing this codec.
 *
 * <p>An unresolvable {@code type} discriminator — JSON naming a subtype this codec never registered
 * — is never silently swallowed into {@code null}: Jackson's default behavior for an unmapped type
 * id is to throw {@link com.fasterxml.jackson.databind.exc.InvalidTypeIdException}, and this codec
 * does nothing to soften that; it only wraps the checked {@link JsonProcessingException} Jackson
 * throws (of which {@code InvalidTypeIdException} is one) as an unchecked {@link
 * IllegalArgumentException}, cause preserved, so callers never have to declare a checked exception
 * to read a park row.
 */
final class StateCodec {

  private final ObjectMapper mapper;

  StateCodec(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    this.mapper = withMixins(mapper.copy());
  }

  private static ObjectMapper withMixins(ObjectMapper copy) {
    copy.addMixIn(ContentBlock.class, ContentBlockMixin.class);
    copy.addMixIn(ToolResolution.class, ToolResolutionMixin.class);
    copy.addMixIn(Decision.class, DecisionMixin.class);
    copy.addMixIn(AgendaItem.class, AgendaItemMixin.class);
    copy.addMixIn(ConversationState.class, ConversationStateMixin.class);
    return copy;
  }

  String writeState(ConversationState state) {
    return write(state);
  }

  ConversationState readState(String json) {
    return read(json, ConversationState.class);
  }

  String writeAgendaItem(AgendaItem entry) {
    return write(entry);
  }

  AgendaItem readAgendaItem(String json) {
    return read(json, AgendaItem.class);
  }

  String writeToolCall(ToolCall call) {
    return write(call);
  }

  ToolCall readToolCall(String json) {
    return read(json, ToolCall.class);
  }

  String writeMessage(Message message) {
    return write(message);
  }

  Message readMessage(String json) {
    return read(json, Message.class);
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("failed to serialize " + value.getClass(), e);
    }
  }

  private <T> T read(String json, Class<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("failed to deserialize " + type, e);
    }
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ThinkingBlock.class, name = "thinking"),
    @JsonSubTypes.Type(value = RedactedThinkingBlock.class, name = "redacted_thinking"),
    @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
    @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
    @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
  })
  private interface ContentBlockMixin {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = ToolResolution.Decided.class, name = "decided"),
    @JsonSubTypes.Type(value = ToolResolution.Completed.class, name = "completed"),
  })
  private interface ToolResolutionMixin {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Decision.Allow.class, name = "allow"),
    @JsonSubTypes.Type(value = Decision.Deny.class, name = "deny"),
  })
  private interface DecisionMixin {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = AgendaItem.Told.class, name = "told"),
    @JsonSubTypes.Type(value = AgendaItem.Resolved.class, name = "resolved"),
  })
  private interface AgendaItemMixin {}

  /**
   * {@link ConversationState#isQuiescent()} is a derived predicate, not a record component: with no
   * mixin, Jackson's {@code isX()}-as-getter convention still serializes it as a {@code
   * "quiescent"} field, which then has nowhere to land on the way back in (record deserialization
   * binds only canonical-constructor properties) and fails the round trip. Ignored here rather than
   * disabling unknown-property strictness mapper-wide, which would quietly swallow a genuinely
   * unexpected field instead of failing loudly on it.
   */
  @JsonIgnoreProperties({"quiescent"})
  private interface ConversationStateMixin {}
}
