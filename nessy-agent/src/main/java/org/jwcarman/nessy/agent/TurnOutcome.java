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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
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

  /**
   * The one shared capture (fix round 2, M11): both {@link DefaultAgent#ask} and {@code
   * Console#decideAndAwait} resolve {@code outcome} from exactly this nine-arm switch — two handled
   * cases, seven explicit no-op ones (sealed-grammar etiquette: no {@code default} arm, so a tenth
   * {@link TurnEvent} variant fails this build at both call sites, not just here). {@code public}
   * out of necessity, not design: {@code Console} lives in {@code org.jwcarman.nessy.agent.host}, a
   * different package from this type, so a package-private helper cannot serve both callers — this
   * is wiring reuse, not new public vocabulary.
   *
   * <p>The running text is seeded on {@code ""}, never {@code null} (fix round 2, I2a): a {@code
   * TurnEnded} that completes before any {@code AssistantSaid} ever narrated (a reply-less
   * completion) resolves {@code Replied("")} rather than NPEing inside {@link Replied}'s own
   * constructor — a {@code null} seed would throw there, and because that throw happens inside a
   * {@code subscribe}d observer, {@link org.jwcarman.nessy.agent.TurnFanout}'s per-subscriber
   * isolation logs and drops it, so {@code outcome} would never complete and the caller's {@code
   * join()} would hang forever.
   */
  static TurnObserver capturing(CompletableFuture<TurnOutcome> outcome) {
    Objects.requireNonNull(outcome, "outcome must not be null");
    AtomicReference<String> lastAssistantText = new AtomicReference<>("");
    return event -> {
      switch (event) {
        case TurnEvent.AssistantSaid said -> lastAssistantText.set(joinedText(said.message()));
        case TurnEvent.TurnEnded ended ->
            outcome.complete(
                ended.failed()
                    ? new Failed(ended.failureReason())
                    : new Replied(lastAssistantText.get()));
        case TurnEvent.TextDelta _ -> {}
        case TurnEvent.ThinkingDelta _ -> {}
        case TurnEvent.RedactedThinking _ -> {}
        case TurnEvent.ToolCallRequested _ -> {}
        case TurnEvent.ToolCallDecided _ -> {}
        case TurnEvent.ToolCallCompleted _ -> {}
        case TurnEvent.ToolCallProgressed _ -> {}
      }
    };
  }

  /** The message's {@link TextBlock} content, concatenated in order — no separator, no filler. */
  private static String joinedText(Message message) {
    StringBuilder joined = new StringBuilder();
    for (ContentBlock block : message.content()) {
      if (block instanceof TextBlock(String text)) {
        joined.append(text);
      }
    }
    return joined.toString();
  }
}
