package org.jwcarman.nessy.spi.inference;

import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * Performs one inference against a provider.
 *
 * <p>The wire, and nothing else. No agent exists at this level: this is handed a context that
 * somebody else chose and returns what came back. The seam exists so the drainer can be tested with
 * no model behind it, and so a provider's wire shape stays on the far side of it.
 *
 * <p><b>Why this is public rather than hidden behind {@link InferenceService}.</b> Not every
 * inference belongs to an agent's conversation. An assembler that summarises has to call a model,
 * and it wants its own context assembled its own way -- going through the service would send it
 * back through assembly and recurse. It calls this instead.
 *
 * <p>Total for expected conditions: a provider that cannot answer returns {@link
 * InferenceResult.Fault}, so no caller can forget a {@code try}. Bugs still throw.
 *
 * <p><b>This is also the whole of streaming, and there is deliberately no second interface for
 * it.</b> A provider that streams narrates fragments as they arrive and still returns the complete
 * result; one that does not narrates nothing and returns the same thing. The engine cannot tell
 * them apart, the fold receives exactly one result either way, and no caller has to choose between
 * two shapes of provider.
 *
 * <p>That works because deltas are narration rather than data: they are best-effort, never stored,
 * and a watcher that misses every one of them still sees the answer land. Making streaming a second
 * return channel would have meant promising something about fragments that nothing here can keep.
 */
public interface InferenceProvider {

  /**
   * @param narrator where to say what is arriving, for a provider that streams. Already bound to
   *     the agent being served, because {@link InferenceRequest} carries no identity on purpose and
   *     a narrator that took one would hand it straight back. Best-effort: anything thrown by it is
   *     the engine's problem, not the provider's.
   */
  InferenceResult infer(InferenceRequest request, AgentNarrator narrator);

  /** For a provider with nothing to stream, and for a caller with nobody watching. */
  default InferenceResult infer(InferenceRequest request) {
    return infer(request, AgentNarrator.silent());
  }

  /**
   * The vendor, as OpenTelemetry's GenAI semantic conventions name it for {@code
   * gen_ai.provider.name}: {@code openai}, {@code anthropic}, {@code gcp.gemini}, {@code
   * aws.bedrock}. Every adapter says so; anything else is named for the class that wrote it.
   */
  default String providerName() {
    return nameOf(getClass());
  }

  /**
   * A class's simple name, or for a lambda or an anonymous class the name of the class that wrote
   * it. The name becomes a metric tag, so it has to be stable: a lambda's own name carries an
   * address that differs between runs, and an anonymous class has none at all.
   */
  private static String nameOf(Class<?> type) {
    if (type.isAnonymousClass() && type.getEnclosingClass() != null) {
      return type.getEnclosingClass().getSimpleName();
    }
    String name = type.getName();
    int lambda = name.indexOf("$$Lambda");
    if (lambda >= 0) {
      String writer = name.substring(0, lambda);
      return writer.substring(Math.max(writer.lastIndexOf('.'), writer.lastIndexOf('$')) + 1);
    }
    return type.getSimpleName();
  }
}
