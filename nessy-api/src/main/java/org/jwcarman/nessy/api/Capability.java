package org.jwcarman.nessy.api;

/**
 * Something a provider may be able to do, that a caller may want used.
 *
 * <p><b>Asked for, never assumed.</b> A request states what it would LIKE, and a provider that
 * cannot oblige simply does not. That way one harness configuration runs against several vendors
 * without the caller branching on which one it got -- asking for prompt caching is harmless
 * everywhere, and only the adapters that have it do anything about it.
 *
 * <p>In the api rather than the spi, even though what a vendor can do is a fact about vendors,
 * because an application is what asks: {@code InferenceConfig} is where one is named, api may not
 * name spi, and a capability nobody can request is not worth having.
 *
 * <p><b>Only what something reads.</b> A capability with no adapter behind it is a promise to an
 * application that nothing keeps, so this list grows when an adapter is written that acts on one --
 * not in anticipation of one.
 */
public enum Capability {

  /**
   * The model may reason visibly before answering, and what it reasoned is carried back as a {@link
   * org.jwcarman.nessy.api.block.Block.Provider} block so the next turn can return it intact.
   */
  THINKING,

  /**
   * Repeated prefixes may be cached, so a long standing prompt is not paid for on every turn.
   *
   * <p>The saving is real and grows with the conversation: the system prompt, the tool
   * declarations, and everything said before this turn are all prefix.
   */
  PROMPT_CACHING,

  /**
   * Prefix caching with a longer retention than the provider's default, for a conversation whose
   * turns are further apart than that default holds.
   */
  PROMPT_CACHING_1H
}
