package org.jwcarman.nessy.api.tool;

/**
 * What became of a late answer.
 *
 * <p>Returned rather than thrown, because the caller is almost always serving a request from
 * somewhere -- a webhook, a queue consumer, a page -- and every arm here maps to a different thing
 * to tell whoever is on the other end. A stale link is an ordinary Tuesday, not an exception.
 *
 * <p><b>None of these is a quiet no-op</b>, and that is the point of the type. A dropped answer
 * strands two parties at once: the agent, which waits out a deadline for something already decided,
 * and the person who answered and reasonably believes they are done.
 */
public sealed interface ReplyOutcome {

  /** The agent has been told, and the call is settled. */
  record Settled() implements ReplyOutcome {}

  /**
   * Nothing is waiting for this answer.
   *
   * <p>Deliberately does not say which of the three it was -- answered already, expired at its
   * deadline, or settled by something else a moment sooner. They mean the same thing to a caller,
   * and telling them apart would need a record of settled calls that nothing else wants and
   * somebody would have to sweep.
   */
  record NotAwaiting() implements ReplyOutcome {}

  /**
   * Not an address this engine issued.
   *
   * <p>Forged, edited, or minted under a key that has since been dropped. Authenticity is all that
   * is checked here: a token that reads cleanly says only that we issued it, never that the call it
   * names is still waiting.
   */
  record Unreadable() implements ReplyOutcome {}
}
