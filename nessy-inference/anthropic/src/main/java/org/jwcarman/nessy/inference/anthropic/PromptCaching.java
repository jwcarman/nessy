package org.jwcarman.nessy.inference.anthropic;

/**
 * Whether this provider marks the stable prefix of every request for Anthropic's prompt cache, and
 * for how long the cache keeps it.
 *
 * <p>A deployment decision rather than a per-call one: a cache write costs more than an ordinary
 * token, so it pays for an agent whose system prompt and tools are long and repeated round after
 * round, and is a bill for nothing for an agent that never repeats a prefix. An application with
 * both kinds builds a provider for each.
 */
public enum PromptCaching {
  OFF,
  FIVE_MINUTES,
  ONE_HOUR
}
