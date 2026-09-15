package org.jwcarman.nessy.engine.inference;

import java.util.List;
import java.util.UUID;
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
  private final InferenceRecorder recorder;

  public DefaultInferenceService(
      InferenceContextAssembler assembler,
      InferenceProvider provider,
      SystemPromptSource systemPrompt,
      List<ToolOffer> tools,
      Narrator narrator,
      InferenceRecorder recorder) {
    this.assembler = assembler;
    this.provider = provider;
    this.systemPrompt = systemPrompt;
    this.tools = List.copyOf(tools);
    this.narrator = narrator;
    this.recorder = recorder;
  }

  @Override
  public InferenceResult infer(InferenceInvocation invocation) {
    // Resolved here, on the dispatcher's thread and off the agent's row lock, so a prompt
    // that needs to look something up may.
    InferenceRequest request =
        new InferenceRequest(
            systemPrompt.forAgent(invocation.agentId()),
            assembler.assemble(invocation),
            // The same offer on every call of this agent type. Varying what is on offer
            // mid-conversation leaves calls in the story for tools the model can no longer
            // see, which reads to it as having imagined them.
            tools,
            invocation.options());
    // Written down before the provider is asked, so a call that never returns still has its
    // context on record; the outcome follows. A provider that throws is a fault like any other.
    UUID recorded = recorder.begin(invocation.agentType(), invocation.agentId(), request);
    InferenceResult result;
    try {
      // Bound here, which is the only place that knows both who is being served and
      // where the narration goes. The provider is handed something that can say what is
      // arriving and cannot say whose it is.
      result =
          provider.infer(request, narrator.forAgent(invocation.agentType(), invocation.agentId()));
    } catch (RuntimeException e) {
      recorder.failed(recorded);
      throw e;
    }
    recorder.end(recorded, result);
    return result;
  }
}
