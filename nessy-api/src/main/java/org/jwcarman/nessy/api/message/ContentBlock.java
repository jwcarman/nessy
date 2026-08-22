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
package org.jwcarman.nessy.api.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One piece of a message. Messages are lists of these, not strings.
 *
 * <p>Carries a {@code "type"} discriminator naming the record on the wire (substrate spec §7):
 * {@code text}, {@code image}, {@code thinking}, {@code redacted-thinking}, {@code tool-use},
 * {@code tool-result}. The values are a compatibility surface and must never change.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
  @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
  @JsonSubTypes.Type(value = ThinkingBlock.class, name = "thinking"),
  @JsonSubTypes.Type(value = RedactedThinkingBlock.class, name = "redacted-thinking"),
  @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool-use"),
  @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool-result")
})
public sealed interface ContentBlock
    permits TextBlock,
        ToolUseBlock,
        ToolResultBlock,
        ThinkingBlock,
        RedactedThinkingBlock,
        ImageBlock {}
