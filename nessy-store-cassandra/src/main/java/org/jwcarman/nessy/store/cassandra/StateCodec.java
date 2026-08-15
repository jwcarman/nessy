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
package org.jwcarman.nessy.store.cassandra;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.ImageBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;

/**
 * Jackson (de)serialization for the one shape {@link CassandraTranscript} persists as a Cassandra
 * {@code text} column: one {@link Message} per {@code nessy_transcript} row.
 *
 * <p>This is the message half of {@code nessy-store-jdbc}'s own {@code StateCodec}, reproduced here
 * rather than depended on — design §2 rules the duplication deliberate, two stores sharing a wire
 * format by specification, not by dependency. {@link ContentBlock} is not itself annotated;
 * annotating {@code nessy-core}'s API type for one storage backend's wire format would leak a
 * storage concern into the core module. Instead it gets a private mixin here, registered with
 * {@link ObjectMapper#addMixIn} on a {@link ObjectMapper#copy() copy} of the mapper the caller
 * hands in — the caller's own mapper, however it is used elsewhere in the harness, is never mutated
 * by constructing this codec.
 *
 * <p>An unresolvable {@code type} discriminator — JSON naming a subtype this codec never registered
 * — is never silently swallowed into {@code null}: Jackson's default behavior for an unmapped type
 * id is to throw {@link com.fasterxml.jackson.databind.exc.InvalidTypeIdException}; this codec only
 * wraps the checked {@link JsonProcessingException} Jackson throws (of which {@code
 * InvalidTypeIdException} is one) as an unchecked {@link IllegalArgumentException}, cause
 * preserved, so callers never have to declare a checked exception to read a row.
 */
final class StateCodec {

  private final ObjectMapper mapper;

  StateCodec(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    this.mapper = mapper.copy().addMixIn(ContentBlock.class, ContentBlockMixin.class);
  }

  String writeMessage(Message message) {
    try {
      return mapper.writeValueAsString(message);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("failed to serialize " + message.getClass(), e);
    }
  }

  Message readMessage(String json) {
    try {
      return mapper.readValue(json, Message.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("failed to deserialize " + Message.class, e);
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
}
