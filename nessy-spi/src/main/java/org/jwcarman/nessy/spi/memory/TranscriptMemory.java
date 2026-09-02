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
package org.jwcarman.nessy.spi.memory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.message.HistoryMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.spi.codec.Codecs;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The record of what happened, as a {@link Memory}.
 *
 * <p>Two of them, and the pair is the choice:
 *
 * <ul>
 *   <li>{@link #eternal} recalls everything that ever happened. Faithful, and it grows forever —
 *       fine for a test or a short conversation, and a slow way to break a long-lived agent.
 *   <li>{@link #recent} recalls the newest history that fits in a character budget. Bounded, and
 *       the oldest turns are simply forgotten.
 * </ul>
 *
 * <p><b>Why characters rather than messages.</b> The failure being prevented is a context too
 * LARGE, and a message count does not bound size: one file read or one {@code docker logs} dump
 * overflows the window at a count of one. Characters are a crude proxy for tokens and are stated as
 * one — nothing in this API estimates tokens, and this does not pretend to.
 *
 * <p><b>The budget is a query, not a scan.</b> A bounded recall reads newest-first and stops once
 * the budget is spent, so it costs the history it KEEPS rather than the history that exists. The
 * older shape read a fixed tail of 500 messages and trimmed it in memory — a cap that existed only
 * because the store could not answer the question, and which quietly kept less than would have fit
 * whenever those messages were small.
 */
public final class TranscriptMemory implements Memory {

  private static final String SELECT_ALL =
      "SELECT payload FROM nessy_transcript "
          + "WHERE agent_type = ? AND agent_id = ? ORDER BY seq";
  private static final String SELECT_NEWEST =
      "SELECT payload, chars FROM nessy_transcript "
          + "WHERE agent_type = ? AND agent_id = ? ORDER BY seq DESC";
  private static final String NEXT_SEQ =
      "SELECT coalesce(max(seq), 0) + 1 FROM nessy_transcript "
          + "WHERE agent_type = ? AND agent_id = ?";
  private static final String DELETE_ALL =
      "DELETE FROM nessy_transcript WHERE agent_type = ? AND agent_id = ?";
  private static final String INSERT =
      "INSERT INTO nessy_transcript (agent_type, agent_id, seq, payload, chars) "
          + "VALUES (?, ?, ?, ?, ?)";

  private final JdbcClient jdbc;
  private final Codec<HistoryMessage> codec = Codecs.factory().create(HistoryMessage.class);
  private final String agentType;
  private final int maxCharacters;

  private TranscriptMemory(DataSource dataSource, AgentType agentType, int maxCharacters) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.jdbc = JdbcClient.create(dataSource);
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null").name();
    this.maxCharacters = maxCharacters;
  }

  /** Everything that ever happened, in order. */
  public static TranscriptMemory eternal(DataSource dataSource, AgentType agentType) {
    return new TranscriptMemory(dataSource, agentType, Integer.MAX_VALUE);
  }

  /**
   * The newest history fitting in {@code maxCharacters}, oldest first.
   *
   * @throws IllegalArgumentException if {@code maxCharacters} is not positive
   */
  public static TranscriptMemory recent(
      DataSource dataSource, AgentType agentType, int maxCharacters) {
    if (maxCharacters < 1) {
      throw new IllegalArgumentException("maxCharacters must be at least 1");
    }
    return new TranscriptMemory(dataSource, agentType, maxCharacters);
  }

  @Override
  public Context recall(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    if (maxCharacters == Integer.MAX_VALUE) {
      return Context.of(
          jdbc
              .sql(SELECT_ALL)
              .params(agentType, agentId.value())
              .query((row, number) -> decode(row.getString("payload")))
              .list()
              .stream()
              .map(ContextMessage.class::cast)
              .toList());
    }
    return Context.of(newestWithinBudget(agentId));
  }

  @Override
  public void remember(AgentId agentId, HistoryMessage message) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(message, "message must not be null");
    // Counted once, here, so a recall never has to decode a message to find out whether it fits.
    jdbc.sql(INSERT)
        .params(
            agentType,
            agentId.value(),
            nextSeq(agentId),
            new String(codec.encode(message), StandardCharsets.UTF_8),
            (long) charactersOf(message))
        .update();
  }

  /**
   * Drops every row this agent wrote. Silent when there are none: an agent that never spoke and an
   * agent whose words have been deleted are the same agent afterwards.
   *
   * <p>Keyed on the TYPE and the id, like every read and write here, because an id names an agent
   * only within its type — forgetting on the id alone would take somebody else's transcript with
   * it.
   */
  @Override
  public void forget(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    jdbc.sql(DELETE_ALL).params(agentType, agentId.value()).update();
  }

  private long nextSeq(AgentId agentId) {
    return jdbc.sql(NEXT_SEQ).params(agentType, agentId.value()).query(Long.class).single();
  }

  /**
   * Walks backwards from the newest, keeping what fits.
   *
   * <p>Backwards because the recent past is what a conversation needs; the first message to
   * overflow the budget ends the walk, and everything older is forgotten rather than partially
   * kept. A single message larger than the whole budget is still kept — a context of nothing is
   * worse than a context of one thing that is too big, and the provider will say so.
   *
   * <p>The cursor is newest-first and the walk stops at the first message that does not fit, so a
   * recall reads the history it keeps rather than the history that exists.
   */
  private List<ContextMessage> newestWithinBudget(AgentId agentId) {
    List<ContextMessage> kept =
        jdbc.sql(SELECT_NEWEST)
            .params(agentType, agentId.value())
            .query(
                // A ResultSetExtractor rather than a row mapper, because this must STOP: the walk
                // ends at the first message that will not fit, and everything older is never read
                // at all. A row-at-a-time callback would visit every message an agent ever sent.
                (java.sql.ResultSet rows) -> {
                  List<ContextMessage> newest = new ArrayList<>();
                  long spent = 0;
                  while (rows.next()) {
                    long size = rows.getLong("chars");
                    if (!newest.isEmpty() && spent + size > maxCharacters) {
                      break;
                    }
                    newest.add(decode(rows.getString("payload")));
                    spent += size;
                  }
                  return newest;
                });
    List<ContextMessage> oldestFirst = new ArrayList<>(kept);
    Collections.reverse(oldestFirst);
    return oldestFirst;
  }

  private HistoryMessage decode(String payload) {
    return codec.decode(payload.getBytes(StandardCharsets.UTF_8));
  }

  /** How much of the budget a message spends. Text only: it is the part that is large. */
  private static int charactersOf(HistoryMessage message) {
    return switch (message) {
      case UserMessage user -> charactersIn(user.content());
      case AnswerMessage answer -> charactersIn(answer.content());
      case ExchangeMessage exchange -> {
        int total = charactersIn(exchange.content());
        for (ToolResultBlock result : exchange.results()) {
          total += charactersIn(result.content());
        }
        yield total;
      }
    };
  }

  private static int charactersIn(List<? extends Block> blocks) {
    int total = 0;
    for (Block block : blocks) {
      if (block instanceof TextBlock text) {
        total += text.text().length();
      }
    }
    return total;
  }
}
