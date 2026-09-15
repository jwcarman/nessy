package org.jwcarman.nessy.examples.chatweb;

import java.util.List;
import java.util.function.Function;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * A model that answers by looking at where the conversation stands, so it needs no memory of its
 * own and a test can run as many agents through it as it likes.
 */
final class ScriptedProvider implements InferenceProvider {

  private final Function<InferenceRequest, InferenceResult> script;

  private ScriptedProvider(Function<InferenceRequest, InferenceResult> script) {
    this.script = script;
  }

  /** Says the same thing to everything. */
  static InferenceProvider alwaysSaying(String text) {
    return new ScriptedProvider(
        request -> new InferenceResult.Answer(List.of(new Block.Text(text))));
  }

  /** Asks for one tool the first time it is consulted in a turn, and answers once it has run. */
  static InferenceProvider callingOnce(String callId, String tool, String arguments, String then) {
    return new ScriptedProvider(
        request -> {
          boolean called =
              request.context().turns().stream()
                  .anyMatch(turn -> !turn.complete() && !turn.exchanges().isEmpty());
          return called
              ? new InferenceResult.Answer(List.of(new Block.Text(then)))
              : new InferenceResult.Actions(
                  List.of(
                      new Block.Commentary("I will send that."),
                      new Block.ToolCall(callId, tool, arguments)));
        });
  }

  @Override
  public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
    return script.apply(request);
  }
}
