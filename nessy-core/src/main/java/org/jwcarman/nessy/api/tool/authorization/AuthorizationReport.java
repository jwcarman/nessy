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
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;

/**
 * An agent's authorization story, generated from its own grants' wiring — never a second place to
 * declare anything, and never a call that perturbs evaluation (design of record
 * 2026-08-16-authorization §8: "the report is the wiring"). Building one reads {@link
 * ToolGrant#tool()}, {@link ToolGrant#policy()}, {@link ToolGrant#enrichers()}, and {@link
 * ToolGrant#contributor()}'s own {@link ActionContributor#displayName()} — by declaration, never by
 * reflection over an erased lambda (action-wave spec §1) — and never calls {@link
 * ActionContributor#actionOf}, {@link Enricher#enrich}, or {@link UsagePolicy#evaluate}. Since it
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
   * ({@code policy instanceof UsagePolicy.Static}) exactly (design §1): when a grant's policy is a
   * canonical static verdict, the executor never renders an action and never runs an enricher for
   * it, no matter what the grant's own {@code enrichers()} list happens to hold — so this reports
   * that list as empty too, honest about what actually runs rather than what is merely declared.
   */
  private static GrantStory story(ToolGrant grant) {
    Tool<?> tool = grant.tool();
    UsagePolicy<?> policy = grant.policy();
    boolean actionRendered = !(policy instanceof UsagePolicy.Static);
    Optional<String> contributorName =
        actionRendered ? grant.contributor().displayName() : Optional.empty();
    List<String> enricherNames = actionRendered ? enricherNames(grant.enrichers()) : List.of();
    return new GrantStory(
        tool.name(), actionRendered, contributorName, enricherNames, policySummary(policy));
  }

  private static List<String> enricherNames(List<Enricher<?>> enrichers) {
    List<String> names = new ArrayList<>();
    for (int i = 0; i < enrichers.size(); i++) {
      int position = i + 1;
      names.add(enrichers.get(i).displayName().orElseGet(() -> "enricher " + position));
    }
    return names;
  }

  /**
   * The canonical statics render as their own factory names ({@code allow()}, {@code
   * deny("reason")}, {@code requireApproval()}); any other policy — a rung-1 lambda pinned via
   * {@code UsagePolicy.of}, or a named class like a threshold policy — reports its own {@code
   * getClass().getSimpleName()}, the one identity every policy already carries without a new field
   * to declare. {@code UsagePolicy.requireApproval()}'s canonical singleton is checked by reference
   * equality ahead of that fallback — the same motivation the framework's own {@code Allow}/{@code
   * Deny} classes are named types rather than bare lambdas (design of record
   * 2026-08-16-authorization §8) — because its own {@code getClass().getSimpleName()} would
   * otherwise print {@code "RequireApproval"}, not the canonical factory call the docs promise.
   * Reference equality, not an {@code instanceof} on a named type, because that canonical class is
   * package-private to {@code org.jwcarman.nessy.api.tool} and this report lives one package over;
   * the singleton {@link UsagePolicy#requireApproval()} always returns is itself the only handle
   * this report needs.
   */
  private static String policySummary(UsagePolicy<?> policy) {
    if (policy instanceof UsagePolicy.Static staticPolicy) {
      return switch (staticPolicy.decision()) {
        case PolicyDecision.Allow _ -> "allow()";
        case PolicyDecision.Deny(String reason) -> "deny(\"" + reason + "\")";
        // Unreachable: Static is sealed to the framework's own Allow/Deny (see its own javadoc),
        // and neither's decision() ever returns RequireApproval — UsagePolicy.requireApproval()
        // deliberately does not implement Static, so it never reaches this switch at all; it is
        // caught by the reference-equality check just below instead. Kept only so this switch
        // stays exhaustive over PolicyDecision's three cases without a default arm masking a
        // future one.
        case PolicyDecision.RequireApproval _ -> "requireApproval()";
      };
    }
    if (policy == UsagePolicy.requireApproval()) {
      return "requireApproval()";
    }
    String simpleName = policy.getClass().getSimpleName();
    return simpleName.isBlank() ? "policy" : simpleName;
  }
}
