package org.jwcarman.nessy.api;

import java.time.Duration;
import java.util.Set;

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
   * Asks for these, if the provider has them.
   *
   * <p>Additive, so several calls accumulate rather than replace: asking for one thing should not
   * quietly withdraw another asked for elsewhere. Nothing here is a guarantee -- a provider that
   * cannot oblige ignores what it does not recognise -- which is what lets the same configuration
   * run against several vendors without branching on which one it got.
   */
  InferenceConfig requesting(Capability... capabilities);

  /** The same, for a set assembled elsewhere -- configuration properties, most often. */
  InferenceConfig requesting(Set<Capability> capabilities);

  /**
   * How many turns of the story to send, counting back from the newest, whole.
   *
   * <p>Turns rather than tokens: an estimate written when an entry is stored and the provider's
   * tokenizer never agree, so a token budget is a number that cannot be checked against the one
   * that decides whether a request is accepted.
   */
  InferenceConfig recentTurns(int turns);

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
