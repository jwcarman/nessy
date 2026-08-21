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
import java.util.function.Supplier;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.tool.authorization.Enricher;

/**
 * A capability and the authority to use it, declared together: which {@link Tool} an agent may
 * call, the {@link ActionContributor} that states what one call will do, the ordered {@link
 * Enricher}s that assess a call before judgment, and the {@link UsagePolicy} the tool call executor
 * consults before it runs — all welded into one {@link Judgment} closure at the point where the
 * types are still live.
 *
 * <p>This is the security statement of the harness, and the {@code grant} factories below are the
 * only supported way to write one. No bare grant, no derived floor, no re-dressing an existing
 * grant with a different policy — a grant does not exist until its authority is answered. The
 * canonical constructor stays public because the records platform requires it, but it enforces no
 * policy or judgment coherence on its own; a caller who builds a {@code ToolGrant} directly,
 * bypassing the factories, forfeits the chokepoint's guarantees. The executor consults only the
 * {@link #judgment()} a grant carries, never the tool directly — the welding the factories perform
 * is what lets the authorization chokepoint run with no unchecked cast anywhere in its own code.
 *
 * <p>Doors rising in rigor (action-wave spec §1, amending design of record 2026-08-16-authorization
 * §1):
 *
 * <ul>
 *   <li>{@link #grant(Tool, UsagePolicy)} — rung 0/1: any {@link Tool}, judged by a policy that
 *       reads at most the context (its action is {@code Object} — the default contributor's own
 *       {@code String.valueOf} of the input).
 *   <li>{@link #grant(Tool, ActionContributor, UsagePolicy)} — rung 2: a typed {@link
 *       ActionContributor} welds {@code A} to the policy at compile time, no enrichers.
 *   <li>{@link #grant(Tool, ActionContributor, List, UsagePolicy)} — rung 2/3: the same typed weld,
 *       plus an ordered list of enrichers over {@code A}.
 * </ul>
 *
 * <p>The application states the action, even for a third-party tool whose own {@link Tool}
 * implementation never speaks for itself — authorization never appears in the tool API (action-wave
 * spec §1).
 */
public record ToolGrant(
    Tool<?> tool,
    UsagePolicy<?> policy,
    List<Enricher<?>> enrichers,
    ActionContributor<?, ?> contributor,
    Judgment judgment) {

  public ToolGrant {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(policy, "policy must not be null");
    enrichers = List.copyOf(Objects.requireNonNull(enrichers, "enrichers must not be null"));
    Objects.requireNonNull(contributor, "contributor must not be null");
    Objects.requireNonNull(judgment, "judgment must not be null");
  }

  /**
   * The grant's welded pipeline, assembled where the types are live so the chokepoint needs no
   * unchecked cast: bind the input by class token, render the action, deposit it under {@link
   * AuthzContext#ACTION_KEY}, run the enrichers in order, and let the policy judge. A {@code
   * RuntimeException} escaping any one stage is caught and rethrown as an {@link
   * IllegalStateException} whose message names the stage that broke ("action stage: ", "enricher
   * stage «name»: ", "policy stage: ") and carries the original as its cause — the chokepoint fails
   * closed on the stage name alone, never on a bare throw.
   */
  @FunctionalInterface
  public interface Judgment {
    Judged decide(AuthzContext context, Object input);
  }

  /** What judging produced: the verdict plus the final context and rendered action (design §9). */
  public record Judged(PolicyDecision decision, AuthzContext context, Object action) {}

  /**
   * Rung 0/1: the default contributor — the approver always sees at least {@code
   * String.valueOf(input)}. No enrichers.
   */
  public static ToolGrant grant(Tool<?> tool, UsagePolicy<Object> policy) {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(policy, "policy must not be null");
    ActionContributor<Object, Object> defaultContributor = String::valueOf;
    return new ToolGrant(
        tool, policy, List.of(), defaultContributor, untypedJudgment(tool, policy));
  }

  /** Rung 2: typed weld, no enrichers. */
  public static <I, A> ToolGrant grant(
      Tool<I> tool, ActionContributor<? super I, A> contributor, UsagePolicy<? super A> policy) {
    return grant(tool, contributor, List.of(), policy);
  }

  /**
   * Rung 2/3: {@code I} comes from the tool, {@code A} from the contributor, welded together at
   * compile time; {@code enrichers} run in order, each extending the context the next one — and
   * finally the policy — sees.
   */
  public static <I, A> ToolGrant grant(
      Tool<I> tool,
      ActionContributor<? super I, A> contributor,
      List<? extends Enricher<? super A>> enrichers,
      UsagePolicy<? super A> policy) {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(contributor, "contributor must not be null");
    List<Enricher<? super A>> ordered =
        List.copyOf(Objects.requireNonNull(enrichers, "enrichers must not be null"));
    Objects.requireNonNull(policy, "policy must not be null");
    Judgment judgment =
        (context, input) -> {
          I typed = tool.inputType().cast(input);
          A action = stage("action stage: ", () -> contributor.actionOf(typed));
          AuthzContext enriched = context.with(AuthzContext.ACTION_KEY, action);
          int index = 0;
          for (Enricher<? super A> enricher : ordered) {
            AuthzContext previous = enriched;
            A renderedAction = action;
            String label = enricher.displayName().orElse("#" + index);
            enriched =
                stage(
                    "enricher stage " + label + ": ",
                    () -> enricher.enrich(previous, renderedAction));
            index++;
          }
          AuthzContext finalContext = enriched;
          A finalAction = action;
          PolicyDecision decision =
              stage("policy stage: ", () -> policy.evaluate(finalContext, finalAction));
          return new Judged(decision, finalContext, finalAction);
        };
    List<Enricher<?>> widened = new ArrayList<>(ordered);
    return new ToolGrant(tool, policy, widened, contributor, judgment);
  }

  private static <T> Judgment untypedJudgment(Tool<T> tool, UsagePolicy<Object> policy) {
    return (context, input) -> {
      Object action = stage("action stage: ", () -> String.valueOf(tool.inputType().cast(input)));
      AuthzContext enriched = context.with(AuthzContext.ACTION_KEY, action);
      PolicyDecision decision = stage("policy stage: ", () -> policy.evaluate(enriched, action));
      return new Judged(decision, enriched, action);
    };
  }

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
