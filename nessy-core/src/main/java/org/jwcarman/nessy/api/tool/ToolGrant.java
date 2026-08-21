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
 * call, the ordered {@link Enricher}s that assess a call before judgment, and the {@link
 * UsagePolicy} the tool call executor consults before it runs — all three welded into one {@link
 * Judgment} closure at the point where the types are still live.
 *
 * <p>This is the security statement of the harness, and there is exactly one way to write it: one
 * of the {@code grant} factories below. No bare grant, no derived floor, no re-dressing an existing
 * grant with a different policy — a grant does not exist until its authority is answered. The
 * executor consults only the {@link #judgment()} a grant carries, never the tool directly — the
 * welding at construction time is what lets the authorization chokepoint run with no unchecked cast
 * anywhere in its own code.
 *
 * <p>Three doors, one rising in rigor from the last (design of record 2026-08-16-authorization §1):
 *
 * <ul>
 *   <li>{@link #grant(Tool, UsagePolicy)} — rung 0/1: any {@link Tool}, judged by a policy that
 *       reads at most the context (its effect is {@code Object} — the tool's own {@code toString}
 *       by default).
 *   <li>{@link #grant(EffectfulTool, List, UsagePolicy)} — rung 2/3: an {@link EffectfulTool} whose
 *       typed effect {@code E} welds to its enrichers and policy at compile time; pass {@code
 *       List.of()} for rung 2 (no enrichers, still typed).
 * </ul>
 *
 * <p>There are deliberately not separate two-arg and three-arg typed doors: an {@link
 * EffectfulTool} is itself a {@link Tool}, so a no-enrichers typed overload would overload-resolve
 * ambiguously against the rung 0/1 door above for every call passing one of the canonical {@code
 * UsagePolicy<Object>} statics (which are valid {@code UsagePolicy<? super E>} witnesses for any
 * {@code E}) — {@code List.of()} costs one token and stays unambiguous.
 */
public record ToolGrant(
    Tool<?> tool, UsagePolicy<?> policy, List<Enricher<?>> enrichers, Judgment judgment) {

  public ToolGrant {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(policy, "policy must not be null");
    enrichers = List.copyOf(Objects.requireNonNull(enrichers, "enrichers must not be null"));
    Objects.requireNonNull(judgment, "judgment must not be null");
  }

  /**
   * The grant's welded pipeline, assembled where the types are live so the chokepoint needs no
   * unchecked cast: bind the input by class token, render the effect, run the enrichers in order,
   * and let the policy judge. A {@code RuntimeException} escaping any one stage is caught and
   * rethrown as an {@link IllegalStateException} whose message names the stage that broke ("effect
   * stage: ", "enricher stage «name»: ", "policy stage: ") and carries the original as its cause —
   * the chokepoint fails closed on the stage name alone, never on a bare throw.
   */
  @FunctionalInterface
  public interface Judgment {
    Judged decide(AuthzContext context, Object input);
  }

  /** What judging produced: the verdict plus the final context and rendered effect (design §9). */
  public record Judged(PolicyDecision decision, AuthzContext context, Object effect) {}

  /** Rung 0/1: any tool, judged by a policy over an {@code Object}-effect context. No enrichers. */
  public static ToolGrant grant(Tool<?> tool, UsagePolicy<Object> policy) {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(policy, "policy must not be null");
    return new ToolGrant(tool, policy, List.of(), untypedJudgment(tool, policy));
  }

  /**
   * Rung 2/3: {@code E} welds tool, enrichers, and policy together at compile time; {@code
   * enrichers} run in order, each extending the context the next one — and finally the policy —
   * sees. Pass {@code List.of()} for rung 2 (typed welding, no enrichers).
   */
  public static <I, E> ToolGrant grant(
      EffectfulTool<I, E> tool,
      List<? extends Enricher<? super E>> enrichers,
      UsagePolicy<? super E> policy) {
    Objects.requireNonNull(tool, "tool must not be null");
    List<Enricher<? super E>> ordered =
        List.copyOf(Objects.requireNonNull(enrichers, "enrichers must not be null"));
    Objects.requireNonNull(policy, "policy must not be null");
    Judgment judgment =
        (context, input) -> {
          I typed = tool.inputType().cast(input);
          E effect = stage("effect stage: ", () -> tool.effect(typed));
          AuthzContext enriched = context;
          int index = 0;
          for (Enricher<? super E> enricher : ordered) {
            AuthzContext previous = enriched;
            E renderedEffect = effect;
            String label = enricher.displayName().orElse("#" + index);
            enriched =
                stage(
                    "enricher stage " + label + ": ",
                    () -> enricher.enrich(previous, renderedEffect));
            index++;
          }
          AuthzContext finalContext = enriched;
          E finalEffect = effect;
          PolicyDecision decision =
              stage("policy stage: ", () -> policy.evaluate(finalContext, finalEffect));
          return new Judged(decision, finalContext, finalEffect);
        };
    List<Enricher<?>> widened = new ArrayList<>(ordered);
    return new ToolGrant(tool, policy, widened, judgment);
  }

  private static <T> Judgment untypedJudgment(Tool<T> tool, UsagePolicy<Object> policy) {
    return (context, input) -> {
      Object effect = stage("effect stage: ", () -> tool.effect(tool.inputType().cast(input)));
      PolicyDecision decision = stage("policy stage: ", () -> policy.evaluate(context, effect));
      return new Judged(decision, context, effect);
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
