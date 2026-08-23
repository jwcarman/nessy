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
package org.jwcarman.nessy.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * What the work came to, as a value (durable spec §8). Infrastructure failures are exceptions; the
 * work's own failure is an {@code Outcome}.
 *
 * <p>Package-private (computation-identity spec §2 addendum, the whittle ruling): every caller
 * lives in {@code org.jwcarman.nessy.agent} — neither desk's public signature carries an {@code
 * Outcome} directly ({@link ApprovalDesk#approve}/{@link ApprovalDesk#deny} and {@link
 * CompletionDesk#complete}/{@link CompletionDesk#fail} take a {@code Decision} or {@code
 * ToolResult}, not this type).
 *
 * <p>{@code Success}'s payload is data-born, not object-born (computation-identity spec §2
 * addendum): every value that ever flows through it is EITHER a {@code ToolResult} (a tool's
 * answer) OR a {@code Decision} (an approval's answer) — not exclusively one, so the component
 * cannot narrow to either alone — so it carries the already-encoded {@link JsonNode}, built through
 * the pinned mapper at the one site ({@link OutcomeCodec#encodeSuccess}) that knows the closed wire
 * vocabulary, rather than a raw {@code Object} a reader would need to downcast blindly.
 */
sealed interface Outcome {

  record Success(JsonNode value) implements Outcome {
    public Success {
      Objects.requireNonNull(value, "value must not be null");
    }
  }

  record Failure(String message) implements Outcome {
    public Failure {
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  record Cancelled(String reason) implements Outcome {
    public Cancelled {
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }
}
