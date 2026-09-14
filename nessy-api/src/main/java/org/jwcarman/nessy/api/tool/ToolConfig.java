package org.jwcarman.nessy.api.tool;

import java.time.Duration;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.Customizers;
import org.jwcarman.nessy.api.RetryPolicy;

/**
 * How one tool is bound to an agent type: how long a call may take, how hard to try it, and who may
 * say no.
 *
 * <p>These are the application's statements ABOUT the tool, never the tool's about itself -- which
 * is what makes a third-party tool governable without wrapping it in a class.
 *
 * @param <I> the tool's bound input
 */
public interface ToolConfig<I> {

  /**
   * How long a call of this tool is worth waiting for. Reaches the tool as {@link
   * ToolCallRequest#deadline()}.
   */
  ToolConfig<I> timeout(Duration timeout);

  /**
   * How hard a failed call of this tool is worth trying again.
   *
   * <p>Defaults to {@link RetryPolicy.Never}, and for most tools that is the right answer
   * permanently: an attempt whose outcome was never observed may well have run, and a tool that
   * changed the world outside the agent must be reconciled rather than repeated. Widen this only
   * for a tool you know to be idempotent.
   */
  ToolConfig<I> retryPolicy(RetryPolicy retryPolicy);

  /**
   * What a call of this tool would actually do, in words a person can consent to.
   *
   * <p>Defaults to the input's own {@code toString()}, which reads well for a record and badly for
   * anything else. Write one for any tool a person will be asked to approve: nobody can consent to
   * {@code {"customer_id":"cus_8823","op":"purge"}}.
   *
   * <p>Here rather than on the {@link Tool}, because a sentence authored by the tool being governed
   * is not a control.
   */
  ToolConfig<I> action(ActionRenderer<I> action);

  /**
   * Adds something to the question before the approver sees it. May be called more than once; they
   * run in the order they were added.
   *
   * <p>Separate from {@link #approver} because gathering and deciding are separate jobs: an
   * enricher never says no, it only makes a fact available. So a risk score, a resolved principal
   * and a quota check can be added independently of each other and of whatever eventually weighs
   * them.
   */
  ToolConfig<I> enrich(ApprovalEnricher enricher);

  /** Who decides whether a call of this tool may run. Defaults to {@link Approver#allow()}. */
  ToolConfig<I> approver(Approver approver, Consumer<ApproverConfig> customizer);

  default ToolConfig<I> approver(Approver approver) {
    return approver(approver, Customizers.withDefaults());
  }
}
