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
package org.jwcarman.nessy.internal;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.ImageBlock;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;

/**
 * Teaches an {@link ObjectMapper} how to round-trip the sealed {@code Message}/{@link ContentBlock}
 * grammar, without either type carrying a single Jackson annotation of its own.
 *
 * <p>{@code Message} and every {@code ContentBlock} variant are plain records: Jackson's built-in
 * record support already reads and writes them, no module needed. The one thing records cannot do
 * unannotated is polymorphism — {@code content: List<ContentBlock>} needs a discriminator to know
 * which variant a given element is. {@link #mapperFor} supplies that as a mixin, entirely from this
 * {@code internal} package, so {@code api} never has to import Jackson to be serializable.
 */
public final class MessageJson {

  private MessageJson() {}

  /**
   * A copy of {@code base} configured to serialize and deserialize {@link ContentBlock}. The
   * argument is untouched; every {@link org.jwcarman.nessy.spi.session.MessageCodec} gets its own
   * configured copy rather than mutating a mapper the caller might be using for something else.
   */
  public static ObjectMapper mapperFor(ObjectMapper base) {
    ObjectMapper mapper = base.copy();
    mapper.addMixIn(ContentBlock.class, ContentBlockMixin.class);
    return mapper;
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
    @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
    @JsonSubTypes.Type(value = ThinkingBlock.class, name = "thinking"),
    @JsonSubTypes.Type(value = RedactedThinkingBlock.class, name = "redacted_thinking"),
    @JsonSubTypes.Type(value = ImageBlock.class, name = "image")
  })
  private interface ContentBlockMixin {}
}
