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

import java.util.Objects;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;

/**
 * What one {@link Agent#ask} settles on (front-ends spec §1): a turn's outcome, as a value, read
 * off the same {@link org.jwcarman.nessy.api.turn.TurnEvent} grammar {@link
 * Agent#subscribe(org.jwcarman.nessy.api.turn.TurnObserver)} already delivers — because the fold
 * retains no failure residue (a failed model turn folds back to {@link Phase.Idle} committing
 * nothing), the events are the only honest source. Zero new event types: {@code Replied} and {@code
 * Failed} resolve from {@code AssistantSaid}/{@code TurnEnded}; {@code Parked} resolves
 * off-channel, through the harness's existing §5a approval notifier (see {@link
 * Harness#awaitApproval(AgentId)}), since a parked call is never narrated at all.
 *
 * <p><b>Module placement note:</b> the front-ends design (spec §1) places this sealed interface in
 * {@code nessy-api}, alongside {@link org.jwcarman.nessy.api.turn.TurnEvent}. It lives in {@code
 * nessy-agent} instead, next to {@link Agent} itself, because {@code Parked} carries {@link
 * ApprovalRequest} — a {@code nessy-spi} type — and {@code nessy-spi} already depends on {@code
 * nessy-api} (its own {@code pom.xml}: {@code ApprovalRequest} itself is built from {@code
 * nessy-api}'s {@link org.jwcarman.nessy.api.tool.ComputationId}, {@link
 * org.jwcarman.nessy.api.tool.ToolCall}, and {@link
 * org.jwcarman.nessy.api.tool.authorization.AuthzContext}); a {@code nessy-api}-hosted {@code
 * TurnOutcome} referencing {@code ApprovalRequest} would need {@code nessy-api} to depend on {@code
 * nessy-spi} too, a cycle. {@code nessy-agent} already depends on both (via {@code nessy-spi}), so
 * this is the nearest module that can actually compile the type the spec describes, without
 * inventing a second, api-level view of {@link ApprovalRequest} the design never asked for.
 */
public sealed interface TurnOutcome {

  /** The turn completed and settled on a reply: the assistant's final text. */
  record Replied(String text) implements TurnOutcome {
    public Replied {
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /** The turn suspended on a §5a approval: the ticket whose id grants or denies it. */
  record Parked(ApprovalRequest ask) implements TurnOutcome {
    public Parked {
      Objects.requireNonNull(ask, "ask must not be null");
    }
  }

  /** The turn ended in failure: {@code TurnEnded}'s own reason, verbatim. */
  record Failed(String reason) implements TurnOutcome {
    public Failed {
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }
}
