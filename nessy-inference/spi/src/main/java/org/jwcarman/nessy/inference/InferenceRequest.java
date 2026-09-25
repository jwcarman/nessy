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
package org.jwcarman.nessy.inference;

import java.util.Objects;
import java.util.Optional;

/**
 * Everything a provider needs for one call.
 *
 * <p>The system prompt sits beside the conversation rather than inside it, which is where most
 * wires put it: a top-level field for Anthropic and Gemini, and a leading message only because that
 * is all an OpenAI-compatible endpoint offers. It is not a turn and it is not a model option.
 *
 * <p><b>Three things decided at three different times.</b> The options are configuration -- which
 * model, how long an answer -- fixed when a harness is built. The toolset is fixed then too, since
 * a tool's shape cannot change between calls. The output schema is the one thing chosen per call,
 * because it comes from what this caller asked to be handed back.
 */
public record InferenceRequest(
    SystemPrompt systemPrompt,
    InferenceContext context,
    Toolset toolset,
    InferenceOptions options,
    Optional<OutputSchema> outputSchema) {

  public InferenceRequest {
    Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(toolset, "toolset must not be null");
    Objects.requireNonNull(options, "options must not be null");
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
  }

  /** Asks for prose: no shape is required of the answer. */
  public InferenceRequest(
      SystemPrompt systemPrompt,
      InferenceContext context,
      Toolset toolset,
      InferenceOptions options) {
    this(systemPrompt, context, toolset, options, Optional.empty());
  }
}
