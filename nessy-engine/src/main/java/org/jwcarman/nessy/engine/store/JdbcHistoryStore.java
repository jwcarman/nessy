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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.engine.token.TokenEstimator;
import org.jwcarman.nessy.engine.tool.ToolCalls;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The story: one row per message, in the order it happened.
 *
 * <p>Not parameterised, and it never will be. {@code <O>} ends at the renderer, so every message
 * stored here is a plain {@link HistoryEntry} and one codec serves every agent type in the system.
 * Only the agent's own document needs a codec built for a caller's type.
 */
@Component
public class JdbcHistoryStore implements TurnHistories, ToolCallHistories {

  private static final String APPEND =
      "INSERT INTO nessy_agent_history"
          + " (agent_type, agent_id, seq, turn_id, tokens, payload)"
          + " VALUES (?, ?, ?, ?, ?, ?)";
  private static final String READ_AT =
      "SELECT payload FROM nessy_agent_history"
          + " WHERE agent_type = ? AND agent_id = ? AND seq = ?";
  private static final String READ_FROM =
      "SELECT tokens, payload FROM nessy_agent_history"
          + " WHERE agent_type = ? AND agent_id = ? AND turn_id >= ? ORDER BY seq";

  /**
   * Where the last {@code n} turns AFTER a boundary begin: the tail once a summary covers the rest.
   *
   * <p>{@link #WINDOW_START} with one more predicate, so the cap is still spent in the query and
   * the boundary is still a whole turn. No {@code COALESCE} here on purpose: no turn after the
   * boundary is a real answer ("nothing left"), not a default of zero that would read the whole
   * story back from the beginning.
   */
  private static final String TAIL_START =
      """
            SELECT MIN(turn_id)
              FROM (SELECT DISTINCT turn_id
                      FROM nessy_agent_history
                     WHERE agent_type = ? AND agent_id = ? AND turn_id > ?
                     ORDER BY turn_id DESC
                     LIMIT ?) recent
            """;

  /**
   * Where the last {@code n} turns begin.
   *
   * <p>A turn boundary by construction: the inner query lists distinct turns newest-first and takes
   * {@code n} of them, so the smallest one it returns is the oldest turn that belongs in the
   * window. Reading {@code turn_id >= } that value then yields whole turns and never half of one --
   * a context beginning with a reply whose observation was trimmed reads as nonsense.
   *
   * <p>{@code COALESCE} to zero so an agent with no history yet returns everything it has, which is
   * nothing, rather than no rows at all for a different reason.
   */
  private static final String TURNS_AFTER =
      "SELECT COUNT(DISTINCT turn_id) FROM nessy_agent_history"
          + " WHERE agent_type = ? AND agent_id = ? AND turn_id > ?";

  private static final String WINDOW_START =
      """
            SELECT COALESCE(MIN(turn_id), 0)
              FROM (SELECT DISTINCT turn_id
                      FROM nessy_agent_history
                     WHERE agent_type = ? AND agent_id = ?
                     ORDER BY turn_id DESC
                     LIMIT ?) recent
            """;

  private static final String PAYLOAD = "payload";

  private final JdbcClient jdbc;
  private final Codec<HistoryEntry> codec;
  private final TokenEstimator tokens;

  public JdbcHistoryStore(JdbcClient jdbc, CodecFactory codecs, TokenEstimator tokens) {
    this.jdbc = jdbc;
    // One codec for every stored entry, whatever the agent type: <O> ends at the renderer, so
    // nothing stored here is parameterised. Only the agent's own document is, and that one is
    // built per harness because it has to be.
    this.codec = codecs.create(HistoryEntry.class);
    this.tokens = tokens;
  }

  /**
   * Writes what a fold decided to record, in the order it decided it.
   *
   * <p>Both numbers come from the message. This used to read {@code MAX(seq)} to find the next one
   * and decide turn boundaries by testing whether a message was an observation -- which put the
   * agent's lifecycle rule inside the code that writes rows, correct only for as long as that rule
   * happened to hold. The fold knows what a turn is; nothing here needs to.
   *
   * <p>The columns are written from the payload rather than asserted beside it, so they are an
   * index over one source of truth. A seq the fold has already used violates the primary key and
   * takes the whole transaction down, which is the right way to find out.
   */
  public List<HistoryStore.Appended> append(
      AgentType agentType, AgentId agentId, List<HistoryEntry> messages) {
    List<HistoryStore.Appended> appended = new ArrayList<>(messages.size());
    for (HistoryEntry message : messages) {
      jdbc.sql(APPEND)
          .params(
              agentType.value(),
              agentId.value(),
              message.seq().value(),
              message.turn().value(),
              tokens.estimate(message),
              codec.encode(message))
          .update();
      appended.add(
          new HistoryStore.Appended(
              message.seq(), message.turn(), message.getClass().getSimpleName()));
    }
    return List.copyOf(appended);
  }

  /**
   * The last {@code turns} turns, whole, most recent last.
   *
   * <p>Turn-shaped because that is the only unit a model can be given. A window over entries can
   * begin with a reply whose observation was trimmed, or end with an observation whose result fell
   * off, and both read as nonsense; a boundary between turns cannot cut one in half.
   *
   * <p>Counting turns rather than tokens on purpose, for now. A token budget has to be estimated
   * when each entry is written, and the estimate and the provider's tokenizer never agree -- so the
   * number that decides what is sent is one nobody can check against the number that decides
   * whether it is accepted. Counting turns is wrong in a way that is at least legible.
   *
   * <p>Two queries whatever the answer's size: find the boundary, then read across it.
   */
  List<Turn> lastTurns(AgentType agentType, AgentId agentId, int turns) {
    Long from =
        jdbc.sql(WINDOW_START)
            .params(agentType.value(), agentId.value(), turns)
            .query(Long.class)
            .optional()
            .orElse(0L);
    return turnsFrom(agentType, agentId, from);
  }

  /**
   * The newest {@code turns} turns strictly after {@code through}, whole, oldest first.
   *
   * <p>Empty when nothing follows the boundary, which the assembler reads as a summary having
   * reached the turn in flight. Two queries whatever the answer's size, as {@link #lastTurns}.
   */
  List<Turn> lastTurnsAfter(AgentType agentType, AgentId agentId, TurnId through, int turns) {
    return jdbc.sql(TAIL_START)
        .params(agentType.value(), agentId.value(), through.value(), turns)
        .query(Long.class)
        .optional()
        .map(from -> turnsFrom(agentType, agentId, from))
        .orElse(List.of());
  }

  /**
   * The story as it was written, entry by entry, from this turn onward.
   *
   * <p>Not on {@link TurnHistory}: that port hands out conversations, and a fold or an assembler
   * has no business reading rows. This is for whoever legitimately wants the record rather than the
   * reading of it -- diagnostics today, an audit or a narration replay later.
   */
  public List<HistoryEntry> entriesFrom(AgentType agentType, AgentId agentId, long fromTurn) {
    return jdbc.sql(READ_FROM)
        .params(agentType.value(), agentId.value(), fromTurn)
        .query((rs, n) -> codec.decode(rs.getBytes(PAYLOAD)))
        .list();
  }

  /**
   * The one entry written at this position, if it is there.
   *
   * <p>By seq rather than by turn, because what wants this is holding an address rather than
   * reading a conversation: an effect row names the entry that obliged it, and the entry is the
   * single copy of what was asked for. {@code (agent_type, agent_id, seq)} is the primary key, so
   * this is one index lookup and there can never be two answers.
   *
   * <p>Empty rather than throwing. A missing entry means the story and an effect row disagree, and
   * whoever asked is mid-obligation -- it needs to discharge that obligation with a failure the
   * model can read, not to die holding it.
   */
  public Optional<HistoryEntry> entryAt(AgentType agentType, AgentId agentId, Seq seq) {
    return jdbc.sql(READ_AT)
        .params(agentType.value(), agentId.value(), seq.value())
        .query((rs, n) -> codec.decode(rs.getBytes(PAYLOAD)))
        .optional();
  }

  /**
   * Every turn from this one onward, whole.
   *
   * <p>The shape an assembler wants: one boundary, with everything behind it represented some other
   * way and everything ahead of it verbatim.
   */
  List<Turn> turnsFrom(AgentType agentType, AgentId agentId, long fromTurn) {
    return Turns.assemble(
        jdbc.sql(READ_FROM)
            .params(agentType.value(), agentId.value(), fromTurn)
            .query((rs, n) -> new Stored(codec.decode(rs.getBytes(PAYLOAD)), rs.getInt("tokens")))
            .list());
  }

  /**
   * This store, narrowed to one agent.
   *
   * <p>Whoever reads history is choosing what to read, not which agent to read it for — that was
   * settled before they were called. Handing out a bound view rather than the store makes reading
   * the wrong agent's history unrepresentable instead of merely wrong, and takes two arguments out
   * of every call that a curator would otherwise have to carry and pass along correctly.
   */
  long turnsAfter(AgentType agentType, AgentId agentId, long through) {
    return jdbc.sql(TURNS_AFTER)
        .params(agentType.value(), agentId.value(), through)
        .query(Long.class)
        .single();
  }

  public TurnHistory forAgent(AgentType agentType, AgentId agentId) {
    return new BoundHistory(this, agentType, agentId);
  }

  @Override
  public ToolCalls forAgentType(AgentType agentType) {
    return new ToolBindingCalls(this, agentType);
  }

  /**
   * Finds one call in the entry that asked for it.
   *
   * <p>Reads the entry rather than the projection, because the projection is built for sending and
   * this is an address lookup -- assembling every turn of a conversation to find one call would do
   * work proportional to the story for a job that is one primary-key hit.
   */
  private record ToolBindingCalls(JdbcHistoryStore store, AgentType agentType)
      implements ToolCalls {

    @Override
    public Optional<ResolvedCall> find(AgentId agentId, Seq requestSeq, CallId callId) {
      return store
          .entryAt(agentType, agentId, requestSeq)
          .filter(HistoryEntry.InferenceRequestedActions.class::isInstance)
          .map(HistoryEntry.InferenceRequestedActions.class::cast)
          .flatMap(
              request ->
                  request.calls().stream()
                      .filter(call -> call.id().equals(callId))
                      .findFirst()
                      .map(call -> new ResolvedCall(request.turn(), call)));
    }
  }

  private record BoundHistory(JdbcHistoryStore store, AgentType agentType, AgentId agentId)
      implements TurnHistory {

    @Override
    public List<Turn> lastTurns(int turns) {
      return store.lastTurns(agentType, agentId, turns);
    }

    @Override
    public List<Turn> turnsFrom(long fromTurn) {
      return store.turnsFrom(agentType, agentId, fromTurn);
    }

    @Override
    public List<Turn> lastTurnsAfter(TurnId through, int turns) {
      return store.lastTurnsAfter(agentType, agentId, through, turns);
    }

    @Override
    public long turnsAfter(long through) {
      return store.turnsAfter(agentType, agentId, through);
    }
  }
}
