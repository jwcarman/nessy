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
package org.jwcarman.nessy.approval.policy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * What a policy SAID. Not how the call settled — that is {@code ApprovalResult}.
 *
 * <p>The two sit close enough to be confused, so the distinction is worth stating: an {@code
 * ApprovalResult} is an outcome the engine acts on, while a verdict is a policy's statement about a
 * request, and {@link Delegate} is not an outcome at all. It is routing.
 *
 * <p><b>Three arms, not four.</b> An earlier draft had an {@code Ask(term)} for "park this and wait
 * for a person". It was redundant: a desk that parks a call and waits IS an {@code Approver}, so
 * asking a person was never a kind of answer — only delegation to a particular approver. Keeping it
 * would have made humans a special case and stopped a policy naming any other kind of reviewer.
 *
 * <p>The consequence is worth saying out loud rather than discovering: <b>a policy cannot park a
 * call itself.</b> It can only name something that can. Parking hands out a capability, and a
 * policy engine — frequently somebody else's service, and one that logs its input — is not trusted
 * with one.
 */
public sealed interface Verdict {

  /** The call may run. */
  record Approve() implements Verdict {}

  /** The call may not run, and this is what to tell the model. */
  record Deny(String reason) implements Verdict {
    public Deny {
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }

  /**
   * Somebody else decides: the approver registered under {@code to}.
   *
   * <p>This is the extension point. A new kind of approval process — a review agent, a change
   * board, a pager — is a registration and a line of policy, with no Java to change here.
   *
   * @param to the name of a registered approver; resolved against an ALLOWLIST, because a policy
   *     that could name any approver in the process could name one that always says yes
   * @param facts whatever else the policy attached — a term, a ticket, a reason. Deposited on the
   *     request before the delegate is asked, so it reads what it understands and ignores the rest.
   *     This rides {@code ApprovalRequest.facts} rather than widening {@code Approver}, an
   *     interface every tool author sees.
   */
  record Delegate(String to, ObjectNode facts) implements Verdict {
    public Delegate {
      Objects.requireNonNull(to, "to must not be null");
      Objects.requireNonNull(facts, "facts must not be null");
      if (to.isBlank()) {
        throw new IllegalArgumentException("a delegate must be named");
      }
    }

    /** Delegates with nothing attached. */
    public Delegate(String to) {
      this(to, JsonNodeFactory.instance.objectNode());
    }
  }

  static Verdict approve() {
    return new Approve();
  }

  static Verdict deny(String reason) {
    return new Deny(reason);
  }

  static Verdict delegate(String to) {
    return new Delegate(to);
  }
}
