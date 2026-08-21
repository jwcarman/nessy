/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.api.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationReport;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.api.tool.authorization.GrantStory;

/**
 * A capability and the authority to use it, declared together: which {@link Tool} an agent may
 * call, the {@link ActionContributor} that states what one call will do, the ordered {@link
 * Enricher}s that assess a call before judgment, and the {@link UsagePolicy} the tool call executor
 * consults before it runs.
 *
 * <p>This is the security statement of the harness, and the {@code grant} factories below are the
 * only supported way to write one — {@code ToolGrant} is a final class with a private constructor
 * (action-wave spec §8): "exactly one way to write it" is now literal, not merely a convention a
 * public canonical constructor could still be bypassed around. No bare grant, no derived floor, no
 * re-dressing an existing grant with a different policy — a grant does not exist until its
 * authority is answered. {@link #judge(AuthzContext, Object)} is the pipeline as behavior; the
 * factories build it where the types are still live so the pipeline itself stays monomorphic (no
 * wildcards on {@link Enricher} or {@link UsagePolicy} anywhere) and the executor needs no
 * unchecked cast.
 *
 * <p>Doors rising in rigor (action-wave spec §1, amending design of record 2026-08-16-authorization
 * §1):
 *
 * <ul>
 *   <li>{@link #grant(Tool, UsagePolicy)} — rung 0/1: any {@link Tool}, judged by a policy that
 *       reads at most the context (its action is {@code Object} — the default contributor's own
 *       {@code String.valueOf} of the input).
 *   <li>{@link #grant(Tool, ActionContributor, UsagePolicy)} — rung 2: a typed {@link
 *       ActionContributor} renders the action, no enrichers.
 *   <li>{@link #grant(Tool, ActionContributor, List, UsagePolicy)} — rung 2/3: the same typed
 *       contributor, plus an ordered list of enrichers.
 * </ul>
 *
 * <p>The application states the action, even for a third-party tool whose own {@link Tool}
 * implementation never speaks for itself — authorization never appears in the tool API (action-wave
 * spec §1).
 */
public final class ToolGrant {

  private static final String TOOL_MUST_NOT_BE_NULL = "tool must not be null";
  private static final String POLICY_MUST_NOT_BE_NULL = "policy must not be null";

  private final Tool<?> tool;
  private final UsagePolicy policy;
  private final List<Enricher> enrichers;
  private final ActionContributor<?, ?> contributor;
  private final Function<Object, Object> renderAction;

  private ToolGrant(
      Tool<?> tool,
      UsagePolicy policy,
      List<Enricher> enrichers,
      ActionContributor<?, ?> contributor,
      Function<Object, Object> renderAction) {
    this.tool = Objects.requireNonNull(tool, TOOL_MUST_NOT_BE_NULL);
    this.policy = Objects.requireNonNull(policy, POLICY_MUST_NOT_BE_NULL);
    this.enrichers = List.copyOf(Objects.requireNonNull(enrichers, "enrichers must not be null"));
    this.contributor = Objects.requireNonNull(contributor, "contributor must not be null");
    this.renderAction = Objects.requireNonNull(renderAction, "renderAction must not be null");
  }

  /** The granted {@link Tool}. */
  public Tool<?> tool() {
    return tool;
  }

  /** The {@link UsagePolicy} the executor consults before the tool runs. */
  public UsagePolicy policy() {
    return policy;
  }

  /** The ordered {@link Enricher}s that assess a call before judgment. */
  public List<Enricher> enrichers() {
    return enrichers;
  }

  /** The {@link ActionContributor} that states what one call will do. */
  public ActionContributor<?, ?> contributor() {
    return contributor;
  }

  /** What judging produced: the verdict plus the final context and rendered action (design §9). */
  public record Judged(PolicyDecision decision, AuthzContext context, Object action) {}

  /**
   * The grant's welded pipeline, run as behavior rather than a stored closure: render the action,
   * deposit it under {@link AuthzContext#ACTION_KEY}, run the enrichers in order, and let the
   * policy judge. A {@code RuntimeException} escaping any one stage is caught and rethrown as an
   * {@link IllegalStateException} whose message names the stage that broke ("action stage: ",
   * "enricher stage «name»: ", "policy stage: ") and carries the original as its cause — the
   * chokepoint fails closed on the stage name alone, never on a bare throw.
   */
  public Judged judge(AuthzContext context, Object input) {
    Objects.requireNonNull(context, "context must not be null");
    ActionOutcome staged =
        stage(
            "action stage: ",
            () -> {
              Object rendered = renderAction.apply(input);
              return new ActionOutcome(rendered, context.with(AuthzContext.ACTION_KEY, rendered));
            });
    AuthzContext enriched = staged.context();
    int index = 0;
    for (Enricher enricher : enrichers) {
      AuthzContext previous = enriched;
      String label = enricher.displayName().orElse("#" + index);
      enriched = stage("enricher stage " + label + ": ", () -> enricher.enrich(previous));
      index++;
    }
    AuthzContext finalContext = enriched;
    PolicyDecision decision = stage("policy stage: ", () -> policy.evaluate(finalContext));
    return new Judged(decision, finalContext, staged.action());
  }

  /**
   * The default rung 0/1 contributor — the approver always sees at least {@code
   * String.valueOf(input)}. Named so {@link AuthorizationReport} reports it honestly as {@code
   * action(String.valueOf)} rather than conflating "the framework's own default" with "a custom
   * contributor the caller simply forgot to name" — the latter reports as {@code action(unnamed)}
   * instead (see {@link GrantStory#render()}).
   */
  private static final ActionContributor<Object, Object> DEFAULT_CONTRIBUTOR =
      ActionContributor.named("String.valueOf", String::valueOf);

  /** Rung 0/1: the default contributor, above. No enrichers. */
  public static ToolGrant grant(Tool<?> tool, UsagePolicy policy) {
    return grant(tool, DEFAULT_CONTRIBUTOR, List.of(), policy);
  }

  /** Rung 2: typed weld, no enrichers. */
  public static <I> ToolGrant grant(
      Tool<I> tool, ActionContributor<? super I, ?> contributor, UsagePolicy policy) {
    return grant(tool, contributor, List.of(), policy);
  }

  /**
   * Rung 2/3: {@code I} comes from the tool, the contributor renders the action, deposited under
   * {@link AuthzContext#ACTION_KEY} before {@code enrichers} run in order, each extending the
   * context the next one — and finally the policy — sees.
   */
  public static <I> ToolGrant grant(
      Tool<I> tool,
      ActionContributor<? super I, ?> contributor,
      List<Enricher> enrichers,
      UsagePolicy policy) {
    Objects.requireNonNull(tool, TOOL_MUST_NOT_BE_NULL);
    Objects.requireNonNull(contributor, "contributor must not be null");
    Objects.requireNonNull(enrichers, "enrichers must not be null");
    Objects.requireNonNull(policy, POLICY_MUST_NOT_BE_NULL);
    Function<Object, Object> renderAction =
        input -> contributor.actionOf(tool.inputType().cast(input));
    return new ToolGrant(tool, policy, new ArrayList<>(enrichers), contributor, renderAction);
  }

  /**
   * The action stage's own outcome — the rendered action alongside the context it was deposited
   * into under {@link AuthzContext#ACTION_KEY} — held together so both the cast and the deposit run
   * inside the same {@link #stage} call: a wrong-typed {@code input} or a {@code null} action
   * (which {@link AuthzContext#with} itself refuses) both fail closed naming the action stage, not
   * a bare {@code ClassCastException} or {@code NullPointerException} escaping the chokepoint
   * unnamed.
   */
  private record ActionOutcome(Object action, AuthzContext context) {}

  /**
   * Runs {@code action}; a {@code RuntimeException} it throws is caught and rethrown as an {@link
   * IllegalStateException} whose message is {@code stagePrefix} plus the original's own message (or
   * its class name, if the message is {@code null}), with the original set as cause.
   */
  private static <R> R stage(String stagePrefix, Supplier<R> action) {
    try {
      return action.get();
    } catch (RuntimeException e) {
      String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      throw new IllegalStateException(stagePrefix + detail, e);
    }
  }
}
