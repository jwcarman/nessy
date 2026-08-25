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
package org.jwcarman.nessy.api.tool.authorization;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.api.tool.approval.Approvers;

/**
 * An agent's authorization story, generated from its own grants' wiring — never a second place to
 * declare anything, and never a call that perturbs evaluation (design of record
 * 2026-08-16-authorization §8: "the report is the wiring"). Building one reads {@link
 * ToolGrant#tool()}, {@link ToolGrant#approver()}, {@link ToolGrant#enrichers()}, and {@link
 * ToolGrant#contributor()}'s own {@link ActionContributor#displayName()} — by declaration, never by
 * reflection over an erased lambda (action-wave spec §1) — and never calls {@link
 * ActionContributor#actionOf}, {@link Enricher#enrich}, or {@link Approver#approve}. Since it
 * cannot drift from the wiring it reads, it is the audit surface's own raw material.
 */
public final class AuthorizationReport {

  private final List<GrantStory> grants;

  private AuthorizationReport(List<GrantStory> grants) {
    this.grants = grants;
  }

  /** One story per grant, ordered by tool name so the report reads the same across builds. */
  public static AuthorizationReport of(Collection<ToolGrant> grants) {
    Objects.requireNonNull(grants, "grants must not be null");
    List<GrantStory> stories =
        grants.stream()
            .map(AuthorizationReport::story)
            .sorted(Comparator.comparing(GrantStory::toolName))
            .toList();
    return new AuthorizationReport(stories);
  }

  /** This report's stories, one per grant, ordered by tool name. */
  public List<GrantStory> grants() {
    return grants;
  }

  /** Every story's own {@link GrantStory#render()}, one per line. */
  public String render() {
    return grants.stream()
        .map(GrantStory::render)
        .collect(Collectors.joining(System.lineSeparator()));
  }

  /**
   * Reads one grant's story. {@code actionRendered} mirrors the chokepoint's own rung-0 test
   * ({@code approver instanceof Approvers.Static}) exactly (approval-lifecycle spec §1.4): when a
   * grant's approver is a canonical static answer, the executor never builds a request and never
   * runs an enricher for it, no matter what the grant's own {@code enrichers()} list happens to
   * hold — so this reports that list as empty too, honest about what actually runs rather than what
   * is merely declared.
   */
  private static GrantStory story(ToolGrant grant) {
    Tool<?> tool = grant.tool();
    Approver approver = grant.approver();
    boolean actionRendered = !(approver instanceof Approvers.Static);
    Optional<String> contributorName =
        actionRendered ? grant.contributor().displayName() : Optional.empty();
    List<String> enricherNames = actionRendered ? enricherNames(grant.enrichers()) : List.of();
    return new GrantStory(
        tool.name(), actionRendered, contributorName, enricherNames, approverSummary(approver));
  }

  private static List<String> enricherNames(List<Enricher> enrichers) {
    List<String> names = new ArrayList<>();
    for (int i = 0; i < enrichers.size(); i++) {
      int position = i + 1;
      names.add(enrichers.get(i).displayName().orElseGet(() -> "enricher " + position));
    }
    return names;
  }

  /**
   * The canonical statics render as their own factory names ({@code allow()}, {@code
   * deny("reason")}); any other approver — a bare lambda, a rule ladder, a named class — reports
   * its own {@code getClass().getSimpleName()}, the one identity every approver already carries
   * without a new field to declare. {@code Approvers.defer()} cannot be told apart by identity (it
   * is a lambda), so it summarises by class name like any other non-static approver, or {@code
   * "approver"} when that name is blank.
   */
  private static String approverSummary(Approver approver) {
    if (approver instanceof Approvers.Static fixed) {
      return switch (fixed.answer()) {
        case Approval.Approved _ -> "allow()";
        case Approval.Denied(String reason, var _) -> "deny(\"" + reason + "\")";
      };
    }
    String simpleName = approver.getClass().getSimpleName();
    return simpleName.isBlank() ? "approver" : simpleName;
  }
}
