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
 * One step of a ladder (spec §1.4): answers, passes, or says "park it". Three outcomes is the
 * toolkit's vocabulary, never {@link Approver}'s — "I am unable to decide" is a rule's word.
 */
@FunctionalInterface
public interface Rule {

  Verdict judge(ApprovalRequest request);

  default Optional<String> displayName() {
    return Optional.empty();
  }

  static Rule named(String displayName, Rule delegate) {
    Objects.requireNonNull(displayName, "displayName must not be null");
    if (displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
    Objects.requireNonNull(delegate, "delegate must not be null");
    return new Rule() {
      @Override
      public Verdict judge(ApprovalRequest request) {
        return delegate.judge(request);
      }

      @Override
      public Optional<String> displayName() {
        return Optional.of(displayName);
      }
    };
  }

  sealed interface Verdict {
    record Answered(Approval approval) implements Verdict {
      public Answered {
        Objects.requireNonNull(approval, "approval must not be null");
      }
    }

    record Undecided() implements Verdict {}

    record Defer() implements Verdict {}
  }
}
