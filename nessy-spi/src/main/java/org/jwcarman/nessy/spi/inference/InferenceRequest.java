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
package org.jwcarman.nessy.spi.inference;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.SystemPrompt;

/**
 * Everything a provider needs for one call.
 *
 * <p>The system prompt sits beside the conversation rather than inside it, which is where most
 * wires put it: a top-level field for Anthropic and Gemini, and a leading message only because that
 * is all an OpenAI-compatible endpoint offers. It is not a turn and it is not a model option.
 */
public record InferenceRequest(
    SystemPrompt systemPrompt,
    InferenceContext context,
    List<ToolOffer> tools,
    ToolChoice toolChoice,
    InferenceOptions options) {

  public InferenceRequest {
    Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(tools, "tools must not be null");
    // Absent means auto, on the wire and in a stored row alike: a request recorded before this
    // field existed said nothing about choosing, which is exactly what auto means. Defaulted
    // rather than refused, because those rows are read back to show what a model was shown.
    toolChoice = toolChoice == null ? ToolChoice.auto() : toolChoice;
    Objects.requireNonNull(options, "options must not be null");
    tools = List.copyOf(tools);
  }

  /** A request that leaves the choice to the model, which is what a turn wants. */
  public InferenceRequest(
      SystemPrompt systemPrompt,
      InferenceContext context,
      List<ToolOffer> tools,
      InferenceOptions options) {
    this(systemPrompt, context, tools, ToolChoice.auto(), options);
  }

  /**
   * Whether anything is on offer.
   *
   * <p>Worth asking rather than sending an empty array. Several OpenAI-compatible servers reject
   * {@code "tools": []}, and a model offered nothing should be asked the way it was asked before
   * tools existed at all.
   */
  public boolean hasTools() {
    return !tools.isEmpty();
  }
}
