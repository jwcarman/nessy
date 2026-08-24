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

/**
 * The answer to an approval question — the payload an approval computation completes with
 * (agent-as-scope spec, §4.3 amendment): the desk records the adjudication as a {@code Decision},
 * and the gate reads it back on re-drive.
 */
public sealed interface Decision {

  /** Run it. */
  record Allow() implements Decision {
    private static final Allow INSTANCE = new Allow();
  }

  /**
   * Do not run it. The reason goes into context so the model can adapt.
   *
   * <p>Rejects a null or blank {@code reason} here, in the compact constructor, rather than in
   * whichever door happens to construct one (e.g. {@code ApprovalDesk#deny} in {@code nessy-agent})
   * — an invariant on the value itself holds for every caller (a hand-rolled {@code
   * DecisionCodec.decode} included), not just today's one production caller. Without it, a null
   * {@code reason} would encode a JSON null that decodes back as the literal string {@code "null"},
   * and an empty one would fold silently — neither throws today.
   */
  record Deny(String reason) implements Decision {

    public Deny {
      Objects.requireNonNull(reason, "reason must not be null");
      if (reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }
  }

  /** The one instance of {@link Allow}; the record has no state, so one is enough. */
  static Decision allow() {
    return Allow.INSTANCE;
  }
}
