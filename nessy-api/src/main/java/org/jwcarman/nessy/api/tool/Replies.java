package org.jwcarman.nessy.api.tool;

/**
 * Where an answer arrives when it did not come back from the call that asked for it.
 *
 * <p>The other half of {@link org.jwcarman.nessy.api.Awaited.Deferred}: that says "later", and this
 * is where later happens. An approver posted a question to a person and returned; a tool queued a
 * job and returned. Hours or days on, something has the answer, and this is the door it comes
 * through.
 *
 * <p><b>Not on a harness, and not because of tidiness.</b> A {@link ReplyToken} is opaque, so
 * whoever holds one cannot tell which kind of agent it belongs to -- which means they could never
 * choose a harness to call. The token names the agent type, and this resolves it. It is also
 * nothing to do with an application's observation type, and a webhook answering an approval should
 * not have to name one.
 *
 * <p><b>The caller is rarely the approver or the tool.</b> It is a webhook controller, a queue
 * consumer, an admin page -- code somewhere else entirely that holds nothing but the token. That is
 * why this is injected rather than handed out at the point of deferral.
 *
 * <p>Answering is idempotent in the only way that matters: a second answer for the same call is
 * refused with {@link ReplyOutcome.NotAwaiting} rather than folded twice.
 */
public interface Replies {

  /**
   * A verdict on a call that was waiting for one.
   *
   * <p>Approving does not run the tool here -- it records the permission and lets the agent
   * dispatch the call as its own piece of durable work. So this returns as soon as the agent has
   * been told, not when the tool has finished, and a slow tool never holds a webhook open.
   *
   * @param token the address the approver was given
   * @param result approved or denied, optionally carrying a reference to the record behind it
   */
  ReplyOutcome approve(ReplyToken token, ApprovalResult result);

  /**
   * A result for a call whose tool deferred.
   *
   * <p>Kept apart from {@link #approve} because the two answer different questions, and a token
   * minted for one is refused by the other. A call still awaiting permission cannot be settled with
   * a result -- that would run past the gate rather than through it.
   */
  ReplyOutcome complete(ReplyToken token, ToolResult result);
}
