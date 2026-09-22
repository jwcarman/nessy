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
package org.jwcarman.nessy.engine.store;

import java.time.Instant;
import org.jwcarman.codec.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.engine.agent.AgentState;

/**
 * One agent type's state, as states rather than as rows.
 *
 * <p>Holds the two things a fold should not: the codec that turns a state into bytes, and the shape
 * of the row those bytes live in. A fold's business is deciding what the next state is; that a
 * state is stored as a payload beside a version and a type name is this table's business, and a
 * state machine carrying that knowledge is a state machine that has to be edited when the schema
 * changes.
 *
 * <p><b>Per harness, not shared.</b> {@code Codec<AgentState<O>>} is composed from the caller's own
 * observation type, so unlike the effect and history codecs it cannot be one instance serving every
 * agent type. That is the whole reason this class exists rather than the fold simply using {@link
 * AgentStateRepository} directly.
 *
 * <p><b>Transaction-agnostic.</b> Both methods are called from inside the fold's transaction and
 * neither opens one. That is not an oversight: locking, deciding, and writing the state, the story
 * and the effects all have to commit together, so the boundary belongs to the fold. A store that
 * opened its own would let a state change commit while the effect it owed rolled back.
 *
 * @param <O> the observation type, which reaches here inside the state's backlog
 */
public class AgentStateStore<O> {

  private final AgentType agentType;
  private final Codec<AgentState<O>> codec;
  private final AgentStateRepository rows;

  public AgentStateStore(
      AgentType agentType, Codec<AgentState<O>> codec, AgentStateRepository rows) {
    this.agentType = agentType;
    this.codec = codec;
    this.rows = rows;
  }

  /**
   * Takes the agent's row for update, creating an idle one if this agent has never been heard of.
   *
   * <p>Coming into being here rather than through a separate call is deliberate: there is nothing
   * to say about an agent before its first observation, and a create step would only be a way to
   * get that wrong.
   *
   * @param at when, used only if the agent is being created
   */
  public Locked<O> lockOrCreate(AgentId agentId, Instant at) {
    AgentStateRow row =
        rows.findAndLockByAgentId(agentId.value())
            .orElseGet(
                () -> {
                  // Nothing to lock yet. Put the row there -- or find that another first fold
                  // beat us to it -- and then lock it like any other, so two first observations
                  // of one agent are serialised exactly as the third and fourth would be.
                  rows.insertIfAbsent(
                      AgentStateRow.initial(
                          agentId.value(),
                          agentType.value(),
                          AgentState.Idle.class.getSimpleName(),
                          codec.encode(new AgentState.Idle<O>(Seq.NONE)),
                          at));
                  return rows.findAndLockByAgentId(agentId.value())
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "agent "
                                      + agentId.value()
                                      + " vanished between insert and lock"));
                });
    return new Locked<>(row, codec.decode(row.payload()));
  }

  /** Writes the state a fold decided on, over the row it was decided from. */
  public void save(Locked<O> locked, AgentState<O> next, Instant at) {
    rows.save(locked.row().folded(next.getClass().getSimpleName(), codec.encode(next), at));
  }

  /**
   * An agent's state, and the row it came out of.
   *
   * <p>The row is carried so that saving can write over the version it was read at, and is of no
   * interest to whoever holds this -- a fold reads {@link #state()} and passes the whole thing back
   * to {@link #save}. It is here rather than hidden behind an interface because the only caller
   * lives one package away and an opaque handle would be ceremony for its own sake.
   */
  public record Locked<O>(AgentStateRow row, AgentState<O> state) {}
}
