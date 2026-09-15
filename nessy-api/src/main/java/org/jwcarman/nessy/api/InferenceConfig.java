package org.jwcarman.nessy.api;

import java.time.Duration;

/**
 * How this agent type infers: what to call, what to send, how long to wait, how hard to try.
 *
 * <p>The binding for the inference effect, and the shape {@code ToolConfig} mirrors for a tool
 * call. Everything here has a default, taken from the factory where the application configured one
 * once.
 */
public interface InferenceConfig {

  /** Which model. Defaults to the factory's. */
  InferenceConfig model(String modelName);

  /** How much answer it may have. Zero or unset leaves it to the provider. */
  InferenceConfig maxTokens(int maxTokens);

  /**
   * How the context for the call is built: summaries, the tail, and background. See {@link
   * ContextConfig} for the order those are sent in and what each one means.
   */
  InferenceConfig context(java.util.function.Consumer<ContextConfig> customizer);

  /**
   * How long the agent is willing to wait for an answer, measured from when the work is written
   * down -- queueing included, because queueing is the agent waiting.
   */
  InferenceConfig timeout(Duration timeout);

  /**
   * How hard a failed inference is worth repeating.
   *
   * <p>The one binding most applications widen: a provider's 503 is the canonical retryable
   * failure, and a call that never reached a model changed nothing by being repeated.
   */
  InferenceConfig retryPolicy(RetryPolicy retryPolicy);
}
