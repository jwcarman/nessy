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
package org.jwcarman.nessy.engine.agent;

import java.util.Objects;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.model.Usage;

/**
 * Everything an agent persists, and no more.
 *
 * <p><b>No backlog and no observation content.</b> The backlog is a table of its own, and the
 * observation a turn is working is a claim id. So this document is a turn id, a phase and two short
 * strings no matter how much work the agent has done or how large its observations are.
 *
 * <p><b>Why {@code observation} is not cleared when a turn ends.</b> The finished claim id is
 * exactly what the next take must name so the store can sweep the right row. One field serves the
 * working turn and the sweep, and an agent that is idle holding an id has simply finished that one.
 *
 * <p><b>Why the tally is here.</b> A turn may make several model calls — ask, run tools, ask again
 * — and the closing line reports what the whole turn cost. Four numbers survive a crash for free;
 * an instance field on an actor does not, and a turn that recovered would report a fraction of what
 * it spent as though that were the whole bill.
 *
 * @param turnId the backlog row this turn came from — one observation is one turn
 * @param phase what is being waited on
 * @param observation the claim id holding the rendered observation
 * @param usage what this turn has cost so far, unreported until a provider says
 */
public record AgentState(TurnId turnId, Phase phase, String observation, Usage usage) {

  public AgentState {
    Objects.requireNonNull(phase, "phase must not be null");
    usage = usage == null ? Usage.unreported() : usage;
  }

  public static AgentState idle() {
    return new AgentState(null, new Phase.Idle(), null, Usage.unreported());
  }

  /** Whether a turn is running. */
  public boolean busy() {
    return !(phase instanceof Phase.Idle);
  }

  /** The tool calls in flight. Asking an agent that is not working tools is a bug, not a query. */
  public Phase.WorkingTools working() {
    if (phase instanceof Phase.WorkingTools tools) {
      return tools;
    }
    throw new IllegalStateException("not working tools: " + phase);
  }

  /** The same turn, at a new phase. */
  public AgentState at(Phase next) {
    return new AgentState(turnId, next, observation, usage);
  }

  /** Adds what one model call reported, keeping whichever halves it actually gave. */
  public AgentState spending(Usage reported) {
    return new AgentState(turnId, phase, observation, add(usage, reported));
  }

  /** A turn begins: the backlog row's id IS the turn id, and its claim holds the input. */
  public AgentState taking(TurnId newTurnId, String observationClaim) {
    return new AgentState(
        newTurnId, new Phase.CallingModel(), observationClaim, Usage.unreported());
  }

  /**
   * The turn is over.
   *
   * <p>The turn id STAYS. Being idle is a fact about the phase, not about whether an id is present,
   * and the id is exactly what the next take must name so the backlog sweeps the right row — naming
   * it is what separates a turn that finished from a take the agent never recorded. It is also what
   * the instructions this decision returns are still working against: they remember, they narrate,
   * and they release, all of which are keyed by turn.
   */
  public AgentState finished() {
    return new AgentState(turnId, new Phase.Idle(), observation, usage);
  }

  /**
   * Sums two reports half by half.
   *
   * <p>A null half stays null rather than becoming zero. A provider that reports an input total and
   * no cache detail is the ordinary case, and zero reads on a graph as a free turn rather than an
   * unmeasured one.
   */
  private static Usage add(Usage running, Usage reported) {
    if (reported == null) {
      return running;
    }
    return new Usage(
        sum(running.inputTokens(), reported.inputTokens()),
        sum(running.outputTokens(), reported.outputTokens()),
        sum(running.cacheReadInputTokens(), reported.cacheReadInputTokens()),
        sum(running.cacheWriteInputTokens(), reported.cacheWriteInputTokens()));
  }

  private static Integer sum(Integer running, Integer reported) {
    if (running == null) {
      return reported;
    }
    return reported == null ? running : running + reported;
  }
}
