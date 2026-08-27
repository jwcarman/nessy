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
package org.jwcarman.nessy.examples.watchman.pekko;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * One entry in the watchman's transcript.
 *
 * <p>Richer than the spike's {@code List<String>} on purpose: the real port speaks the OpenAI tool
 * protocol properly, so an assistant turn carries its tool-call ids and a result carries the id it
 * answers. The spike could flatten everything to prose; a real port cannot, because the model needs
 * the ids back exactly as it issued them.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "turn")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Turn.User.class, name = "user"),
  @JsonSubTypes.Type(value = Turn.Assistant.class, name = "assistant"),
  @JsonSubTypes.Type(value = Turn.ToolResult.class, name = "tool-result")
})
public sealed interface Turn {

  record User(String text) implements Turn {}

  /** What the model said, and what it asked to run. Either half may be empty. */
  record Assistant(String text, List<ToolRequest> calls) implements Turn {
    public Assistant {
      calls = List.copyOf(calls);
    }
  }

  record ToolResult(String callId, String tool, String text) implements Turn {}

  /** One call the model asked for, with the id it will expect back. */
  record ToolRequest(String id, String tool, String argumentsJson) {}
}
