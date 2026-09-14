package org.jwcarman.nessy.api.tool;

import static org.jwcarman.nessy.api.Awaited.ready;
import static org.jwcarman.nessy.api.tool.ApprovalResult.approved;

import org.jwcarman.nessy.api.Awaited;

/**
 * Decides whether one call may run, or says that somebody else will.
 *
 * <p>A facade over anything: a rule, a risk service, an OPA query, a Slack post, a four-eyes
 * workflow, a person at a terminal. None of that is visible to the engine, and all of it is free to
 * be slow -- an approver that needs a human returns {@link Awaited#deferred()} and answers later
 * through {@link Replies}.
 *
 * <p><b>Deferring is not free, and it is worth saying so plainly.</b> "Just return deferred" reads
 * easier than it is: an approver that defers takes on a ledger. It must keep the {@link
 * ApprovalRequest#replyToken()}, because nothing else can ever settle that call; and it usually
 * wants the deadline and whatever it sent -- a message id, a ticket -- so it can tidy up a question
 * that expires unanswered. The engine keeps none of that on its behalf, deliberately: the thing
 * that decided a person was needed is the only thing that knows which person, and a second ledger
 * in the engine could only ever drift from the real one.
 *
 * <p>Nothing tells an approver that its question expired. The deadline it was given is the whole of
 * what it knows, which is enough to sweep its own outstanding questions.
 */
public interface Approver {

  /**
   * @param request what is being asked, including the sentence a person is to consent to and the
   *     address a late answer goes to
   * @return a verdict now, or {@link Awaited#deferred()} if one is coming later
   */
  Awaited<ApprovalResult> approve(ApprovalRequest request);

  /**
   * Always yes -- for a tool nobody gates.
   *
   * <p>A real approver rather than an absence to check for, so the engine has one path into a
   * running tool rather than a branch on whether a gate is present. The path that is never
   * exercised is the one a misconfiguration would take.
   */
  static Approver allow() {
    return _ -> ready(approved());
  }
}
