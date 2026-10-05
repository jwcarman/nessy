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
package org.jwcarman.nessy.engine.effect;

import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.Attempt;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
import tools.jackson.databind.node.ObjectNode;

/**
 * How a performed effect gets back into the agent it was performed for.
 *
 * <p>Separate from {@code QueuedHarness} on purpose. The harness is the door callers hold, and its
 * only method is {@code tell}; if delivering an outcome were on it too, any caller could invent an
 * answer the model never gave and fold it into an agent's story. Splitting the two means the
 * capability exists for the machinery that has earned it and is not on the type a user is handed.
 *
 * <p>The implementation is the harness -- the fold has to happen behind the same row lock an input
 * takes -- but nobody coding against a harness ever sees that.
 */
public interface AgentEffectCallback {

  /**
   * Folds an outcome into the agent that owed the effect.
   *
   * <p>Called after the work happened and before the effect row is retired, so a crash between the
   * two leaves a row that comes due again -- and the fold, seeing no call outstanding, ignores the
   * second delivery rather than answering twice.
   *
   * @param turn the turn whose effect this answers, read off the effect row itself -- the fold
   *     ignores an outcome that names a turn it is not on, which is what stops a late answer being
   *     written down as the current turn's. Empty only when the effect row could not be decoded at
   *     all, and there is therefore nothing to read the turn from; the fold then settles for
   *     whatever turn the agent is on, which is all a corrupt row can support.
   * @param request where the request that asked for this call sits, read off the effect row in the
   *     same way, for an outcome that settles a call of a request; the fold ignores an answer for a
   *     request other than the one the agent is waiting on, which is what stops a call id repeated
   *     across two requests of one turn from letting a late answer settle the later one. Empty for
   *     an inference, which answers no request -- and for the effect row that could not be decoded,
   *     the one answer that cannot name its request, which the fold then matches to the request the
   *     agent is waiting on.
   * @param traceContext the trace of the effect this answers, so whatever the outcome causes stays
   *     in the same turn's trace; null when that effect had none
   * @param priorAttempts what the attempts before this one learned, oldest first. Empty unless the
   *     work was tried more than once, and always empty for a door that does not retry. Carried
   *     here rather than inside the outcome so that the four inference arms -- already a published
   *     grammar -- did not each have to grow a field for it.
   * @return whether the fold wrote at least one event for this outcome. False when the fold ignored
   *     it -- a second answer for a call, an answer for another turn or request -- when the outcome
   *     named no turn and the agent was on none, and when the outcome named no request and the
   *     agent was waiting on none, so it was dropped before the fold. True does not mean anything
   *     was asked of the dispatcher: an accepted denial can leave other calls outstanding and emit
   *     no effect.
   */
  boolean deliverOutcome(
      AgentId agentId,
      Optional<TurnId> turn,
      Optional<Seq> request,
      EffectOutcome outcome,
      String traceContext,
      List<FailedAttempt> priorAttempts);

  /**
   * Records that an attempt deferred, and marks its row as waiting.
   *
   * <p>Called after a handler answered that the work is elsewhere and an answer will arrive later.
   * One locked step has the fold write that the call was deferred -- until the attempt's deadline
   * -- and marks the effect row parked, so the two stand or fall together. When the fold writes
   * nothing, because the call was already answered or is no longer the one the agent is waiting on,
   * the row is not marked.
   *
   * <p>The dispatcher calls this outside the handling of the attempt, and a failure here is only
   * logged: a deferral that could not be recorded leaves the row as it was claimed, and the call
   * stands as it did before.
   *
   * @param attempt the attempt that deferred; its deadline is the instant the deferral stands until
   * @param effect what the attempt was performing
   * @param facts the facts the approver was shown, written on the deferral; an empty object when
   *     there were none, and for a tool's deferral
   */
  void park(Attempt attempt, AgentEffect effect, ObjectNode facts);
}
