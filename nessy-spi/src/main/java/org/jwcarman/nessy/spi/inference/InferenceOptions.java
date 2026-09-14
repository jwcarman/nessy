package org.jwcarman.nessy.spi.inference;

import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.api.Capability;

/**
 * The terms of one inference: which model, and how much of an answer.
 *
 * <p>Per call rather than baked into an adapter, so a single provider connection serves several
 * agent types asking different models. That removes the duplicate that existed while the adapter
 * held a model name of its own.
 *
 * <p>{@code maxTokens} is an OUTPUT ceiling, and is not the curator's budget. The curator's is an
 * input ceiling and stays where it is, because how much of the story to spend is a curation
 * decision rather than a property of the model. Two numbers, opposite directions.
 *
 * @param modelName the provider's name for the model to call
 * @param maxTokens the most the model may generate; not sent at all when it is not positive, which
 *     is how "no ceiling of ours" is said
 * @param requested what this call would like used if the provider has it. Never a guarantee and
 *     never an error: an adapter that cannot oblige ignores what it does not recognise, which is
 *     what lets one harness configuration run against several vendors unchanged.
 */
public record InferenceOptions(String modelName, int maxTokens, Set<Capability> requested) {

  public InferenceOptions {
    Objects.requireNonNull(modelName, "modelName must not be null");
    if (modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    Objects.requireNonNull(requested, "requested must not be null");
    requested = Set.copyOf(requested);
  }

  /** No ceiling of ours, and nothing asked for; whatever the provider defaults to. */
  public static InferenceOptions of(String modelName) {
    return new InferenceOptions(modelName, 0, Set.of());
  }

  /** Whether this call would like {@code capability} used, if the provider has it. */
  public boolean wants(Capability capability) {
    return requested.contains(capability);
  }

  /** Whether a ceiling was asked for at all. */
  public boolean hasMaxTokens() {
    return maxTokens > 0;
  }
}
