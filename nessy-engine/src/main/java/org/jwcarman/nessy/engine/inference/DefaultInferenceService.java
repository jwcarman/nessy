package org.jwcarman.nessy.engine.inference;

import java.util.List;
import org.jwcarman.nessy.api.SystemPromptSource;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.inference.ToolOffer;
import org.jwcarman.nessy.spi.narration.Narrator;

/** Assemble, then send. The only thing that holds both halves at once, and it is one line. */
public class DefaultInferenceService implements InferenceService {

  private final InferenceContextAssembler assembler;
  private final InferenceProvider provider;
  private final SystemPromptSource systemPrompt;
  private final List<ToolOffer> tools;
  private final Narrator narrator;

  public DefaultInferenceService(
      InferenceContextAssembler assembler,
      InferenceProvider provider,
      SystemPromptSource systemPrompt,
      List<ToolOffer> tools,
      Narrator narrator) {
    this.assembler = assembler;
    this.provider = provider;
    this.systemPrompt = systemPrompt;
    this.tools = List.copyOf(tools);
    this.narrator = narrator;
  }

  @Override
  public InferenceResult infer(InferenceInvocation invocation) {
    // Resolved here, on the dispatcher's thread and off the agent's row lock, so a prompt
    // that needs to look something up may.
    return provider.infer(
        new InferenceRequest(
            systemPrompt.forAgent(invocation.agentId()),
            assembler.assemble(invocation),
            // The same offer on every call of this agent type. Varying what is on offer
            // mid-conversation leaves calls in the story for tools the model can no longer
            // see, which reads to it as having imagined them.
            tools,
            invocation.options()),
        // Bound here, which is the only place that knows both who is being served and
        // where the narration goes. The provider is handed something that can say what is
        // arriving and cannot say whose it is.
        narrator.forAgent(invocation.agentType(), invocation.agentId()));
  }
}
