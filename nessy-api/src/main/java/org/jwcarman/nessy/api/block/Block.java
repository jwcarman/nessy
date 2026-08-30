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
package org.jwcarman.nessy.api.block;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One piece of message content that crosses the wire to a provider.
 *
 * <p>That is the whole definition, and it is the test for whether a type belongs here: if it
 * appears inside a message's content array in a provider request or response, it is a {@code
 * Block}; if it does not, it is not. {@code ToolResult} is a tool's answer and never crosses the
 * wire, so it is not a block; {@link ToolResultBlock} is what the engine builds from one, and is.
 *
 * <p>Carries a {@code "type"} discriminator naming the record on the wire: {@code text}, {@code
 * image}, {@code thinking}, {@code redacted-thinking}, {@code tool-use}, {@code tool-result}. The
 * values are a compatibility surface and must never change — note that {@code tool-use} is kept
 * even though the type is now {@link ToolCallBlock}, because stored transcripts name the old value.
 *
 * <p><b>Why the permits clause lists the markers rather than the records.</b> A direct subtype of a
 * sealed type must itself be permitted, and the three markers extend this interface so they inherit
 * the discriminator above. The concrete records reach {@code Block} through whichever markers they
 * carry; {@link ToolResultBlock} carries none, because exactly one container accepts it and that
 * container names it concretely.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
  @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
  @JsonSubTypes.Type(value = ThinkingBlock.class, name = "thinking"),
  @JsonSubTypes.Type(value = RedactedThinkingBlock.class, name = "redacted-thinking"),
  @JsonSubTypes.Type(value = ToolCallBlock.class, name = "tool-use"),
  @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool-result")
})
public sealed interface Block
    permits UserContentBlock, AssistantContentBlock, ToolResultContentBlock, ToolResultBlock {}
