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
 * One thing in a {@link Context} — everything the model is shown.
 *
 * <p><b>The role IS the type.</b> There is no {@code Role} enum, because a second way to ask the
 * same question is a second way to get a different answer — and because each role admits a
 * different set of content, which a shared type could only check at runtime. That is why an {@link
 * Asking} takes tool calls and no plain text, and an {@link AnswerMessage} takes text and no calls.
 *
 * <p>Two kinds, and the split is what happened versus how things are. A {@link HistoryMessage} is a
 * record of an event and belongs in the transcript; an {@link AmbientMessage} is background
 * assembled for one call and thrown away. {@code Memory.remember} takes the former, so the latter
 * has no door into a transcript at all.
 */
/** Wire names are a compatibility surface: a stored transcript names them. Never change one. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "role")
@JsonSubTypes({
  @JsonSubTypes.Type(value = UserMessage.class, name = "user"),
  @JsonSubTypes.Type(value = AnswerMessage.class, name = "assistant"),
  @JsonSubTypes.Type(value = ExchangeMessage.class, name = "asking"),
  @JsonSubTypes.Type(value = AmbientMessage.class, name = "ambient")
})
public sealed interface ContextMessage permits HistoryMessage, AmbientMessage {}
