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
 * One turn of the conversation.
 *
 * <p>The role IS the type. There is no {@code Role} enum, because a second way to ask the same
 * question is a second way to get a different answer — and because each role admits a different set
 * of content, which a shared type could only check at runtime.
 *
 * <p>There is no system arm. Anthropic now accepts a system message inside the message list, as a
 * cache-preserving operator channel, and adding an arm to a sealed interface later is a breaking
 * change — so this is a decision deferred rather than one made.
 */
/** Wire names are a compatibility surface: a stored transcript names them. Never change one. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "role")
@JsonSubTypes({
  @JsonSubTypes.Type(value = UserMessage.class, name = "user"),
  @JsonSubTypes.Type(value = AssistantMessage.class, name = "assistant"),
  @JsonSubTypes.Type(value = ToolResultMessage.class, name = "tool-result")
})
public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {}
