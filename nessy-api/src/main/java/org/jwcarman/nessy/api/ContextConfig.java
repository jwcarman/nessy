package org.jwcarman.nessy.api;

/**
 * How the context for a call is built: what is compressed, how much of the tail is sent whole, and
 * what background rides along.
 *
 * <p>The story an agent is sent is built in one fixed order, and these are its three parts:
 *
 * <pre>
 *   summaries   whatever every {@link Summarizer} returns, in order
 *   tail        every turn after the last summary, capped at {@link #maxTail} -- or, with no
 *               summaries, the last {@code maxTail} turns of the whole story
 *   ambient     whatever every {@link AmbientSource} has to say right now
 * </pre>
 *
 * <p>Nothing here reads history except the tail, and the tail's floor is implicit: the last
 * summary's {@code through}. A gap between two summaries is not filled -- a source that left one
 * out meant to.
 */
public interface ContextConfig {

  /** Adds a source of summaries. Several may be added; their results are concatenated in order. */
  ContextConfig summaries(Summarizer source);

  /**
   * At most this many turns of the tail, counting back from the newest, whole.
   *
   * <p>Turns rather than tokens: an estimate written when an entry is stored and the provider's
   * tokenizer never agree, so a token budget is a number that cannot be checked against the one
   * that decides whether a request is accepted. Defaults to 20.
   */
  ContextConfig maxTail(int turns);

  /**
   * Offers background the model should have in mind, asked afresh on every call.
   *
   * <p>The other half of a tool. A notebook the agent writes to is a tool and one of these; so is a
   * plan it keeps, or a view of a system it is operating. Tool in, background out.
   *
   * <p>Two sources may not offer the same {@link Ambient#kind()} -- refused here rather than at
   * render time, because an adapter would write two sections under one label and the model would
   * see a contradiction with no way to tell which is current.
   */
  ContextConfig ambient(AmbientSource source);

  /** Background that is the same for every agent and every turn. */
  default ContextConfig ambient(Ambient ambient) {
    return ambient(AmbientSource.constant(ambient));
  }
}
