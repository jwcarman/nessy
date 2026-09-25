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
package org.jwcarman.nessy.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Which conversation's events these are.
 *
 * <p><b>TODO -- James: the name.</b> It cannot be {@code AgentId}, which the queued door owns, and
 * it must not be {@code Conversation}, which was permanently rejected. It names where one
 * exchange's events live: a chat session, a CLI invocation, one sub-agent's isolated run.
 *
 * <p>Narrower than an {@link AgentId}: nothing can put work into this but its caller, so nothing is
 * ever coalesced into it, which is why a direct harness may take one and a queued harness may not.
 */
public record ScopeId(String value) {

  public ScopeId {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("a scope that names nothing is not a scope");
    }
  }

  public static ScopeId of(String value) {
    return new ScopeId(value);
  }

  /** A scope nothing else will ever use, for work that is not resumed. */
  public static ScopeId fresh() {
    return new ScopeId(UUID.randomUUID().toString());
  }

  @Override
  public String toString() {
    return value;
  }
}
