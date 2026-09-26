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
import org.jwcarman.nessy.api.Narrator;
import org.jwcarman.nessy.api.SystemPromptSource;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.OutputSchema;
import org.jwcarman.nessy.inference.ToolOffer;
import org.jwcarman.nessy.inference.Toolset;

/** Assemble, then send. The only thing that holds both halves at once, and it is one line. */
public class DefaultInferenceService implements InferenceService {

  private final InferenceContextAssembler assembler;
  private final InferenceProvider provider;
  private final SystemPromptSource systemPrompt;

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
  private final Optional<OutputSchema> outputSchema;

  public DefaultInferenceService(
      InferenceContextAssembler assembler,
      InferenceProvider provider,
      SystemPromptSource systemPrompt,
      List<ToolOffer> tools,
      Narrator narrator,
      Optional<OutputSchema> outputSchema) {
    this.assembler = assembler;
    this.provider = provider;
    this.systemPrompt = systemPrompt;
    this.toolset = Toolset.of(tools);
    this.narrator = narrator;
    this.outputSchema = Objects.requireNonNull(outputSchema, "outputSchema must not be null");
  }

  @Override
  public InferenceResult infer(InferenceInvocation invocation) {
    // Resolved here, on the dispatcher's thread and off the agent's row lock, so a prompt
    // that needs to look something up may.
    InferenceRequest request =
        new InferenceRequest(
            systemPrompt.forAgent(invocation.agentId()),
            assembler.assemble(invocation),
            toolset,
            invocation.options(),
            outputSchema);
    // Bound here, which is the only place that knows both who is being served and where the
    // narration goes. The provider is handed something that can say what is arriving -- text, or
    // thinking -- and cannot say whose it is, or that an agent is involved at all.
    return provider.infer(
        request,
        InferenceNarrators.of(narrator.forAgent(invocation.agentType(), invocation.agentId())));
  }
}
