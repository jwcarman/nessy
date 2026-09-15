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
    InferenceOptions options) {

  public InferenceRequest {
    Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(tools, "tools must not be null");
    Objects.requireNonNull(options, "options must not be null");
    tools = List.copyOf(tools);
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
