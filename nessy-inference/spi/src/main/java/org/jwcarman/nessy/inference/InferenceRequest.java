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
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.SystemPrompt;

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
 *
 * <p><b>A request may be a one-off.</b> An agent's requests are a series: each begins the way the
 * last one did, which is what makes a vendor's prompt cache worth writing to. A request that is a
 * {@link #oneOff() one-off} belongs to no series -- a summariser's, sent once for one chapter -- so
 * nothing it sends will be sent again. That is a fact about the call, and what follows from it is
 * each adapter's business: one that marks what to cache marks nothing, whatever lifetime its
 * properties ask for, since an entry nobody reads back is paid for and never used. An adapter whose
 * vendor caches unasked has nothing to do.
 *
 * <p><b>A request says what it is for.</b> Its {@link #purpose() purpose} -- answering a turn,
 * summarising a chapter -- labels the span and the metrics recorded for the call, so spend can be
 * split by it. A purpose and a one-off are separate things: one says what the call is for, the
 * other whether its prefix will be sent again, and neither implies the other.
 *
 * @param oneOff whether nothing this request sends will be sent again
 * @param purpose what the call is for; never sent to the vendor
 */
public record InferenceRequest(
    SystemPrompt systemPrompt,
    InferenceContext context,
    Toolset toolset,
    InferenceOptions options,
    Optional<JsonSchema> outputSchema,
    boolean oneOff,
    InferencePurpose purpose) {

  public InferenceRequest {
    Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(toolset, "toolset must not be null");
    Objects.requireNonNull(options, "options must not be null");
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    Objects.requireNonNull(purpose, "purpose must not be null");
  }

  /** One of a series, as an agent's requests are, answering its turn. */
  public InferenceRequest(
      SystemPrompt systemPrompt,
      InferenceContext context,
      Toolset toolset,
      InferenceOptions options,
      Optional<JsonSchema> outputSchema) {
    this(systemPrompt, context, toolset, options, outputSchema, false, InferencePurpose.ANSWER);
  }

  /** Asks for prose: no shape is required of the answer. */
  public InferenceRequest(
      SystemPrompt systemPrompt,
      InferenceContext context,
      Toolset toolset,
      InferenceOptions options) {
    this(systemPrompt, context, toolset, options, Optional.empty());
  }

  /** This request as a one-off: nothing it sends will be sent again. */
  public InferenceRequest asOneOff() {
    return new InferenceRequest(
        systemPrompt, context, toolset, options, outputSchema, true, purpose);
  }

  /** This request for another purpose; whether it is a one-off is unchanged. */
  public InferenceRequest withPurpose(InferencePurpose purpose) {
    return new InferenceRequest(
        systemPrompt, context, toolset, options, outputSchema, oneOff, purpose);
  }
}
