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
package org.jwcarman.nessy;

import java.util.Objects;

/**
 * One typed-door subagent declaration (design of record 2026-08-16 §0.5): {@code inputType} pairs
 * with {@code config} so the two travel together through {@link AgentConfig} and {@link
 * SubagentConfig}'s own declaration lists without ever needing an unchecked cast to recover {@code
 * T} — a private generic helper method captures it straight off this record's own type parameter
 * instead (the standard wildcard-capture idiom), which is why this exists as its own type rather
 * than two parallel lists an index could desync.
 *
 * @param <T> the subagent's own input vocabulary — the delegation tool's wire shape
 */
record TypedSubagentDeclaration<T>(Class<T> inputType, SubagentConfig<T> config) {

  TypedSubagentDeclaration {
    Objects.requireNonNull(inputType, "inputType must not be null");
    Objects.requireNonNull(config, "config must not be null");
  }
}
