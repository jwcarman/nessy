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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationReport;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.api.tool.authorization.GrantStory;

/**
 * A capability and the authority to use it, declared together: which {@link Tool} an agent may
 * call, the {@link ActionContributor} that states what one call will do, the ordered {@link
 * Enricher}s that gather facts onto the request, and the {@link Approver} the tool call executor
 * consults before it runs.
 *
 * <p>This is the security statement of the harness, and the {@code grant} factories below are the
 * only supported way to write one — {@code ToolGrant} is a final class with a private constructor
 * (action-wave spec §8): "exactly one way to write it" is now literal, not merely a convention a
 * public canonical constructor could still be bypassed around. No bare grant, no derived floor, no
 * re-dressing an existing grant with a different approver — a grant does not exist until its
 * authority is answered. {@link #request(String, String, ToolCall, Object, ObjectMapper)} binds the
 * input, renders the action, deposits it on the draft, runs the enrichers in order, and freezes —
 * the approver reads the frozen request. The factories build the rendering function where the types
 * are still live so the pipeline itself stays monomorphic (no wildcards on {@link Enricher} or
 * {@link Approver} anywhere) and the executor needs no unchecked cast.
 *
 * <p>Doors rising in rigor (action-wave spec §1, amending design of record 2026-08-16-authorization
 * §1):
 *
 * <ul>
 *   <li>{@link #grant(Tool, Approver)} — rung 0/1: any {@link Tool}, judged by an approver that
 *       reads at most the request (its action is the default contributor's own {@code
 *       String.valueOf} of the input).
 *   <li>{@link #grant(Tool, ActionContributor, Approver)} — rung 2: a typed {@link
 *       ActionContributor} renders the action, no enrichers.
 *   <li>{@link #grant(Tool, ActionContributor, List, Approver)} — rung 2/3: the same typed
 *       contributor, plus an ordered list of enrichers.
 * </ul>
 *
 * <p>The application states the action, even for a third-party tool whose own {@link Tool}
 * implementation never speaks for itself — authorization never appears in the tool API (action-wave
 * spec §1).
 */
public final class ToolGrant {

  private static final String TOOL_MUST_NOT_BE_NULL = "tool must not be null";
  private static final String APPROVER_MUST_NOT_BE_NULL = "approver must not be null";

  private final Tool<?> tool;
  private final Approver approver;
  private final List<Enricher> enrichers;
  private final ActionContributor<?, ?> contributor;
  private final Function<Object, String> renderAction;

  private ToolGrant(
      Tool<?> tool,
      Approver approver,
      List<Enricher> enrichers,
      ActionContributor<?, ?> contributor,
      Function<Object, String> renderAction) {
    this.tool = Objects.requireNonNull(tool, TOOL_MUST_NOT_BE_NULL);
    this.approver = Objects.requireNonNull(approver, APPROVER_MUST_NOT_BE_NULL);
    this.enrichers = List.copyOf(Objects.requireNonNull(enrichers, "enrichers must not be null"));
    this.contributor = Objects.requireNonNull(contributor, "contributor must not be null");
    this.renderAction = Objects.requireNonNull(renderAction, "renderAction must not be null");
  }

  /** The granted {@link Tool}. */
  public Tool<?> tool() {
    return tool;
  }

  /** The {@link Approver} the executor consults before the tool runs. */
  public Approver approver() {
    return approver;
  }

  /**
   * The ordered {@link Enricher}s — the gathering stage: facts onto the request, never verdicts.
   */
  public List<Enricher> enrichers() {
    return enrichers;
  }

  /** The {@link ActionContributor} that states what one call will do. */
  public ActionContributor<?, ?> contributor() {
    return contributor;
  }

  /**
   * Builds the question (approval-lifecycle spec §1.2): a draft from the coordinates, the action
   * rendered and set, each enricher run in order over the draft, then frozen. A {@code
   * RuntimeException} escaping the action render or any enricher is rethrown as an {@link
   * IllegalStateException} naming the stage — the chokepoint fails closed on the stage name.
   *
   * @param agentType the recipe's name
   * @param agentId the scope
   * @param call the tool call being judged
   * @param input the bound tool input
   * @param pinned the harness's pinned mapper, which every fact is rendered through
   * @return the frozen request
   */
  public ApprovalRequest request(
      String agentType, String agentId, ToolCall call, Object input, ObjectMapper pinned) {
    ApprovalRequest.Draft draft = ApprovalRequest.draft(agentType, agentId, call, pinned);
    stage("action stage: ", () -> draft.action(renderAction.apply(input)));
    int index = 0;
    for (Enricher enricher : enrichers) {
      String label = enricher.displayName().orElse("#" + index);
      stage(
          "enricher stage " + label + ": ",
          () -> {
            enricher.enrich(draft);
            return null;
          });
      index++;
    }
    return draft.freeze();
  }

  /**
   * The default rung 0/1 contributor — the approver always sees at least {@code
   * String.valueOf(input)}. Named so {@link AuthorizationReport} reports it honestly as {@code
   * action(String.valueOf)} rather than conflating "the framework's own default" with "a custom
   * contributor the caller simply forgot to name" — the latter reports as {@code action(unnamed)}
   * instead (see {@link GrantStory#render()}).
   */
  private static final ActionContributor<Object, String> DEFAULT_CONTRIBUTOR =
      ActionContributor.named("String.valueOf", String::valueOf);

  /** Rung 0/1: the default contributor, above. No enrichers. */
  public static ToolGrant grant(Tool<?> tool, Approver approver) {
    return grant(tool, DEFAULT_CONTRIBUTOR, List.of(), approver);
  }

  /** Rung 2: typed weld, no enrichers. */
  public static <I> ToolGrant grant(
      Tool<I> tool, ActionContributor<? super I, ?> contributor, Approver approver) {
    return grant(tool, contributor, List.of(), approver);
  }

  /**
   * Rung 2/3: {@code I} comes from the tool, the contributor renders the action, set on the draft
   * before {@code enrichers} run in order, each depositing facts the approver reads.
   */
  public static <I> ToolGrant grant(
      Tool<I> tool,
      ActionContributor<? super I, ?> contributor,
      List<Enricher> enrichers,
      Approver approver) {
    Objects.requireNonNull(tool, TOOL_MUST_NOT_BE_NULL);
    Objects.requireNonNull(contributor, "contributor must not be null");
    Objects.requireNonNull(enrichers, "enrichers must not be null");
    Objects.requireNonNull(approver, APPROVER_MUST_NOT_BE_NULL);
    Function<Object, String> renderAction =
        input ->
            String.valueOf(
                Objects.requireNonNull(
                    contributor.actionOf(tool.inputType().cast(input)),
                    "a contributor must not render a null action"));
    return new ToolGrant(tool, approver, new ArrayList<>(enrichers), contributor, renderAction);
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
