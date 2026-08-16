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
 * of record 2026-08-16-authorization §8): the tool it names, whether its policy is one of the
 * canonical {@code UsagePolicy.Static} verdicts (in which case the ladder law never renders an
 * effect or runs an enricher for it — the rung-0 fast path), the effect type an {@code
 * EffectfulTool} welds, the enrichers that run in order, and the policy's own identity.
 *
 * @param toolName the granted tool's {@code Tool#name()}
 * @param effectRendered {@code false} exactly when {@code policy} is a {@code UsagePolicy.Static}
 *     verdict — the ladder law's rung-0 skip, honestly reflected: no effect is ever rendered for
 *     this grant, and any enrichers it happens to carry never run, regardless of what the wiring
 *     lists
 * @param effectType the statically-known effect type name, empty when the tool is untyped ({@code
 *     Tool}, not {@code EffectfulTool}) or its type argument could not be resolved by reflection
 * @param enrichers the enricher display names, in wiring order — empty whenever {@code
 *     effectRendered} is {@code false}
 * @param policy a human-readable identity for the grant's policy
 */
public record GrantStory(
    String toolName,
    boolean effectRendered,
    Optional<String> effectType,
    List<String> enrichers,
    String policy) {

  public GrantStory {
    Objects.requireNonNull(toolName, "toolName must not be null");
    Objects.requireNonNull(effectType, "effectType must not be null");
    enrichers = List.copyOf(Objects.requireNonNull(enrichers, "enrichers must not be null"));
    Objects.requireNonNull(policy, "policy must not be null");
  }

  /**
   * Renders this story as one line: {@code "name: effect → enricher → ... → policy (identity)"} for
   * an evaluated grant, or plainly {@code "name: identity"} for a rung-0 grant that never renders
   * an effect at all.
   */
  public String render() {
    if (!effectRendered) {
      return toolName + ": " + policy;
    }
    StringBuilder line =
        new StringBuilder(toolName).append(":  ").append(effectType.orElse("Object"));
    for (String enricher : enrichers) {
      line.append(" → ").append(enricher);
    }
    line.append(" → policy (").append(policy).append(')');
    return line.toString();
  }
}
