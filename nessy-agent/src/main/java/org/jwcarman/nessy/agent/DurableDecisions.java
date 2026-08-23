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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.spi.approval.Adjudication;

/**
 * The one mapping from a durable outcome to the approval grammar (spec §4.3 amendment): the
 * approval computation completes with a {@code Decision} — answering "no" is a successful
 * adjudication.
 *
 * <p>{@code mapper} threads through every door here (computation-identity spec §2 addendum): {@link
 * Outcome.Success#value()} is a data-born {@link JsonNode}, not a raw {@link Decision}, so building
 * or reading one needs the pinned mapper's encoding. Package-private (fix round 1, Q4): {@link
 * ApprovalDesk#approve}/{@link ApprovalDesk#deny} are this class's one production caller, and every
 * white-box test over the grant arm's own mechanics lives in this same package (or reaches this
 * door through a locally-built {@link ApprovalDesk} of its own — see {@code GrantSurvivalTest}).
 * There is no cross-package caller left to force this public.
 */
final class DurableDecisions {

  private DurableDecisions() {}

  static Outcome granted(ObjectMapper mapper) {
    return new Outcome.Success(encode(mapper, Decision.allow()));
  }

  static Outcome denied(ObjectMapper mapper, String reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    return new Outcome.Success(encode(mapper, new Decision.Deny(reason)));
  }

  private static JsonNode encode(ObjectMapper mapper, Decision decision) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new OutcomeCodec(mapper).encodeSuccess(decision);
  }

  /**
   * {@code computation} names the id in an unexpected-payload message; it carries no other duty.
   */
  static Adjudication toAdjudication(
      ObjectMapper mapper, Outcome outcome, ComputationId computation) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    Objects.requireNonNull(computation, "computation must not be null");
    return switch (outcome) {
      case Outcome.Success(JsonNode value) -> toAdjudication(mapper, value, computation);
      case Outcome.Failure(String message) -> new Adjudication.Refused(message);
      case Outcome.Cancelled(String reason) -> new Adjudication.Refused("cancelled: " + reason);
    };
  }

  private static Adjudication toAdjudication(
      ObjectMapper mapper, JsonNode payload, ComputationId computation) {
    Object decoded;
    try {
      decoded = new OutcomeCodec(mapper).decodeSuccess(payload);
    } catch (IllegalArgumentException e) {
      return new Adjudication.Refused(
          "unexpected approval payload: " + payload + " (computation " + computation.value() + ")");
    }
    return switch (decoded) {
      case Decision.Allow _ -> new Adjudication.Granted();
      case Decision.Deny(String reason) -> new Adjudication.Refused(reason);
      default ->
          new Adjudication.Refused(
              "unexpected approval payload: "
                  + decoded.getClass().getName()
                  + " (computation "
                  + computation.value()
                  + ")");
    };
  }
}
