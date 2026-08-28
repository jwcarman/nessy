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
package org.jwcarman.nessy.examples.watchman.pekko;

import java.util.Objects;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.TokenEstimator;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * One {@link Memory} per agent, with a token budget on the way out.
 *
 * <p><b>SubstrateMemory owns the transcript directly.</b> The port's hand-rolled {@code Transcript}
 * is gone: {@code SubstrateMemory} already journals one {@link Remembrance} per turn over the same
 * {@link Substrate}, and {@code RemembranceFold} already does the pairing that class had grown by
 * hand — an assistant message naming tool_use ids is WITHHELD from the context until every one of
 * those ids has a result, which is exactly the reconciliation a crash between two non-atomic writes
 * needs. That was about twenty-five lines of ours; it is now none.
 *
 * <p><b>The budget is the reason this class exists at all, and it is a finding.</b> {@code Context}
 * carries {@code limitTokens(budget, estimator)}, pair-safe by construction — and <b>nothing in
 * Nessy calls it.</b> Not {@code SubstrateMemory}, not {@code ProviderModelCallExecutor}, not the
 * starter; {@code ModelSettings.contextWindow} is declared and unused. So {@code recall()} returns
 * the whole conversation and the prompt grows without bound, which is the expensive curve: it is
 * paid in tokens on every call and it exhausts the context window long before the database notices.
 *
 * <p>This is a decorator rather than a new {@code Memory}: the SPI reserves custom implementations
 * for semantic summarization, and applying {@code Context}'s own structural edit is not that. The
 * budget belongs upstream — either in {@code SubstrateMemory} or wherever {@code contextWindow} was
 * meant to be honoured.
 */
public final class Memories {

  private final Substrate substrate;
  private final long budgetTokens;
  private final TokenEstimator estimator = TokenEstimator.heuristic();

  public Memories(Substrate substrate, long budgetTokens) {
    this.substrate = Objects.requireNonNull(substrate, "substrate must not be null");
    this.budgetTokens = budgetTokens;
  }

  /**
   * The transcript for one agent. Blocking — and, as of this branch, NOT always off a dispatcher:
   * {@link AgentActor#startTurnIfWork} is a caller, and it runs inline in {@link AgentActor}'s
   * command handler, on the Pekko dispatcher. That call, plus {@code Backlogs#ingest}, {@code
   * Claims#put}, and {@code Claims#deleteTurn} (see {@link BlockingWork}), are the four places a
   * blocking substrate call now happens on that dispatcher rather than on {@link BlockingWork}'s
   * virtual threads — a starvation risk for the dispatcher {@link ModelWorker} and {@link
   * ModelDesk} also fold on, not a documentation guarantee this method can still make.
   */
  public Memory forAgent(String agentId) {
    Memory delegate = new SubstrateMemory(substrate, agentId, StateSerializer.MAPPER);
    return new Budgeted(delegate, budgetTokens, estimator);
  }

  /** Everything remembered, unbudgeted — what the notes page shows. */
  public Context everything(String agentId) {
    return new SubstrateMemory(substrate, agentId, StateSerializer.MAPPER).recall();
  }

  /** Remembers verbatim; recalls only what fits. */
  private record Budgeted(Memory delegate, long budget, TokenEstimator estimator)
      implements Memory {

    @Override
    public void remember(Remembrance remembrance) {
      delegate.remember(remembrance);
    }

    @Override
    public Context recall() {
      return delegate.recall().limitTokens(budget, estimator);
    }
  }
}
