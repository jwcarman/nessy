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
package org.jwcarman.nessy.api.tool.approval;

import java.util.Objects;
import java.util.Optional;

/**
 * The answer to "may this call run?" (approval-lifecycle spec §1.1). One type, wherever the answer
 * travels: spoken by a grant's approver in-process, by a person at the desk, or delivered by
 * Continuum days later.
 *
 * <p>{@code reference} is an opaque pointer into whatever system produced the answer — its own
 * decision id, a ticket, a hash of its evidence. Nessy never interprets it; it is the join between
 * the fold's record and the audit trail that knows who and why (spec §7).
 */
public sealed interface Approval {

  record Approved(Optional<String> reference) implements Approval {
    public Approved {
      Objects.requireNonNull(reference, "reference must not be null");
    }
  }

  record Denied(String reason, Optional<String> reference) implements Approval {
    public Denied {
      Objects.requireNonNull(reason, "reason must not be null");
      if (reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank");
      }
      Objects.requireNonNull(reference, "reference must not be null");
    }
  }

  static Approval approved() {
    return new Approved(Optional.empty());
  }

  static Approval denied(String reason) {
    return new Denied(reason, Optional.empty());
  }
}
