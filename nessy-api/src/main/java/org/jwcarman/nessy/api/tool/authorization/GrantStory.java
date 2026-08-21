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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One {@code ToolGrant}'s authorization story, read from its wiring, never from running it (design
 * of record 2026-08-16-authorization §8, amended by action-wave spec §1): the tool it names,
 * whether its policy is one of the canonical {@code UsagePolicy.Static} verdicts (in which case the
 * ladder law never renders an action or runs an enricher for it — the rung-0 fast path), the
 * grant's own {@code ActionContributor} display name, the enrichers that run in order, and the
 * policy's own identity.
 *
 * @param toolName the granted tool's {@code Tool#name()}
 * @param actionRendered {@code false} exactly when {@code policy} is a {@code UsagePolicy.Static}
 *     verdict — the ladder law's rung-0 skip, honestly reflected: no action is ever rendered for
 *     this grant, and any enrichers it happens to carry never run, regardless of what the wiring
 *     lists
 * @param actionContributor the contributor's own {@code displayName()} — empty only for a custom
 *     contributor the caller never named; the framework's own default contributor ({@code
 *     ToolGrant}'s {@code String.valueOf}) always carries a name of its own and is never the reason
 *     this is empty
 * @param enrichers the enricher display names, in wiring order — empty whenever {@code
 *     actionRendered} is {@code false}
 * @param policy a human-readable identity for the grant's policy
 */
public record GrantStory(
    String toolName,
    boolean actionRendered,
    Optional<String> actionContributor,
    List<String> enrichers,
    String policy) {

  public GrantStory {
    Objects.requireNonNull(toolName, "toolName must not be null");
    Objects.requireNonNull(actionContributor, "actionContributor must not be null");
    enrichers = List.copyOf(Objects.requireNonNull(enrichers, "enrichers must not be null"));
    Objects.requireNonNull(policy, "policy must not be null");
  }

  /**
   * Renders this story as one line: {@code "name: action(<displayName|unnamed>) → enricher → ... →
   * policy (identity)"} for an evaluated grant, or plainly {@code "name: identity"} for a rung-0
   * grant that never renders an action at all. {@code unnamed} appears only when the grant's own
   * contributor is a bare, undecorated lambda the caller never wrapped in {@code
   * ActionContributor.named(...)} — the framework's own default contributor always renders as
   * {@code action(String.valueOf)} instead.
   */
  public String render() {
    if (!actionRendered) {
      return toolName + ": " + policy;
    }
    StringBuilder line =
        new StringBuilder(toolName)
            .append(": action(")
            .append(actionContributor.orElse("unnamed"))
            .append(')');
    for (String enricher : enrichers) {
      line.append(" → ").append(enricher);
    }
    line.append(" → policy (").append(policy).append(')');
    return line.toString();
  }
}
