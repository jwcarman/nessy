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
package org.jwcarman.nessy.engine.inference;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.Narrator;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.backend.event.InferenceRequestManifest;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.ToolChoice;
import org.jwcarman.nessy.inference.ToolOffer;
import org.jwcarman.nessy.inference.Toolset;

/** Assemble, then send. The only thing that holds both halves at once, and it is one line. */
public class DefaultInferenceService implements InferenceService {

  private final InferenceContextAssembler assembler;
  private final InferenceProvider provider;
  private final SystemPrompt systemPrompt;

  /**
   * The same offer on every call of this agent type. Varying what is on offer mid-conversation
   * leaves calls in the story for tools the model can no longer see, which reads to it as having
   * imagined them.
   */
  private final Toolset toolset;

  private final Narrator narrator;

  /**
   * What every call of this agent type constrains its answer with -- empty for an agent that never
   * asks for a particular shape, which is every queued agent today and an unstructured direct one.
   * Uniform per service rather than per call: a caller that wants a shape says so when the harness
   * is made, the same moment the model and the tools are settled.
   */
  private final Optional<JsonSchema> outputSchema;

  /** Where the parts of each request's manifest are kept, scoped to the agent for each call. */
  private final Payloads payloads;

  public DefaultInferenceService(
      InferenceContextAssembler assembler,
      InferenceProvider provider,
      SystemPrompt systemPrompt,
      List<ToolOffer> tools,
      Narrator narrator,
      Optional<JsonSchema> outputSchema,
      Payloads payloads) {
    this.assembler = assembler;
    this.provider = provider;
    this.systemPrompt = systemPrompt;
    this.toolset = Toolset.of(tools);
    this.narrator = narrator;
    this.outputSchema = Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    this.payloads = Objects.requireNonNull(payloads, "payloads must not be null");
    // Read when the harness is built, so a build whose version resource was never filled in fails
    // at startup with its clear message, not at the first model call.
    EngineVersion.current();
  }

  /** The same offer, asked to produce prose. */
  private Toolset answering() {
    return new Toolset(toolset.offers(), new ToolChoice.Answer());
  }

  @Override
  public Inferred infer(InferenceInvocation invocation) {
    InferenceRequest request =
        new InferenceRequest(
            systemPrompt,
            assembler.assemble(invocation),
            // The same tools, and a request that says to answer rather than call. Left to the
            // adapter rather than decided here: dropping the offers would throw away a cached
            // prefix on every vendor, including the ones whose wire can say this outright.
            invocation.answerOnly() ? answering() : toolset,
            invocation.options(),
            outputSchema);
    // Built from the very request that is sent, before it is sent: a record of what the model
    // was shown cannot disagree with what it was shown. A provider that throws still leaves the
    // parts stored; they are content-addressed, so the retry reuses them rather than adding more.
    InferenceRequestManifest manifest =
        InferenceRequestManifests.of(request, payloads.forAgent(invocation.agentId()));
    // Bound here, which is the only place that knows both who is being served and where the
    // narration goes. The provider is handed something that can say what is arriving -- text, or
    // thinking -- and cannot say whose it is, or that an agent is involved at all.
    return new Inferred(
        provider.infer(
            request,
            InferenceNarrators.of(narrator.forAgent(invocation.agentType(), invocation.agentId()))),
        manifest);
  }
}
