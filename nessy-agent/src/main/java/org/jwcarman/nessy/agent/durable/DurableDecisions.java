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
package org.jwcarman.nessy.agent.durable;

import java.util.Objects;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.Outcome;
import org.jwcarman.nessy.spi.approval.Adjudication;

/**
 * The one mapping from a durable outcome to the approval grammar (spec §4.3 amendment): the
 * approval slot completes with a {@code Decision} — answering "no" is a successful adjudication.
 */
public final class DurableDecisions {

  private DurableDecisions() {}

  public static Outcome granted() {
    return new Outcome.Success(Decision.allow());
  }

  public static Outcome denied(String reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    return new Outcome.Success(new Decision.Deny(reason));
  }

  /** {@code slot} names the id in an unexpected-payload message; it carries no other duty. */
  public static Adjudication toAdjudication(Outcome outcome, ComputationId slot) {
    Objects.requireNonNull(outcome, "outcome must not be null");
    Objects.requireNonNull(slot, "slot must not be null");
    return switch (outcome) {
      case Outcome.Success(Decision.Allow _) -> new Adjudication.Granted();
      case Outcome.Success(Decision.Deny(String reason)) -> new Adjudication.Refused(reason);
      case Outcome.Success(Object value) ->
          new Adjudication.Refused(
              "unexpected approval payload: "
                  + value.getClass().getName()
                  + " (slot "
                  + slot.value()
                  + ")");
      case Outcome.Failure(String message) -> new Adjudication.Refused(message);
      case Outcome.Cancelled(String reason) -> new Adjudication.Refused("cancelled: " + reason);
    };
  }
}
