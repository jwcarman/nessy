package org.jwcarman.nessy.api.tool;

import java.time.Duration;
import org.jwcarman.nessy.api.RetryPolicy;

/** How one approver is bound to a tool. */
public interface ApproverConfig {

  /**
   * How long the question stands. Reaches the approver as {@link ApprovalRequest#deadline()}.
   *
   * <p>Generous by nature: the deferred path is the ordinary one here, because a human takes as
   * long as a human takes.
   */
  ApproverConfig timeout(Duration timeout);

  /**
   * How hard a failure to <em>ask</em> is worth trying again. Defaults to {@link
   * RetryPolicy.Never}.
   *
   * <p><b>This never governs the verdict.</b> A {@link ApprovalResult.Denied} is an answer, not a
   * failure -- the approver was reached and said no -- and re-asking until somebody relents would
   * eventually produce the answer the engine wanted. What this can retry is the approval service
   * being unreachable or the notification never going out: the question not arriving, rather than
   * arriving and being refused.
   */
  ApproverConfig retryPolicy(RetryPolicy retryPolicy);
}
