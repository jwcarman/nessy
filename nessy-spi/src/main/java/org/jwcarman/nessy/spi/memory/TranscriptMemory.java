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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
import org.jwcarman.nessy.spi.substrate.JournalStore;
import org.jwcarman.nessy.spi.substrate.Substrate;

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
 * <p><b>Why the read is bounded too.</b> Recalling by reading the whole journal and discarding most
 * of it would cost more with every turn an agent ever took. {@link Substrate#head} says where the
 * end is, so a bounded recall reads a bounded tail. If those last messages are all small, less
 * history is kept than would have fit — the right direction to be wrong in, and one number to
 * raise.
 */
public final class TranscriptMemory implements Memory {

  /**
   * The most messages a bounded recall will read, however small they are. Sets the cost ceiling of
   * a recall; the character budget then decides how many of them are kept.
   */
  private static final int MAX_MESSAGES_READ = 500;

  private final Substrate substrate;
  private final String kind;
  private final JournalStore<HistoryMessage> journal;
  private final int maxCharacters;

  private TranscriptMemory(Substrate substrate, AgentType agentType, int maxCharacters) {
    this.substrate = Objects.requireNonNull(substrate, "substrate must not be null");
    Objects.requireNonNull(agentType, "agentType must not be null");
    this.kind = "transcript/" + agentType.name();
    this.journal = substrate.journal(kind, HistoryMessage.class);
    this.maxCharacters = maxCharacters;
  }

  /** Everything that ever happened, in order. */
  public static TranscriptMemory eternal(Substrate substrate, AgentType agentType) {
    return new TranscriptMemory(substrate, agentType, Integer.MAX_VALUE);
  }

  /**
   * The newest history fitting in {@code maxCharacters}, oldest first.
   *
   * @throws IllegalArgumentException if {@code maxCharacters} is not positive
   */
  public static TranscriptMemory recent(
      Substrate substrate, AgentType agentType, int maxCharacters) {
    if (maxCharacters < 1) {
      throw new IllegalArgumentException("maxCharacters must be at least 1");
    }
    return new TranscriptMemory(substrate, agentType, maxCharacters);
  }

  @Override
  public Context recall(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    if (maxCharacters == Integer.MAX_VALUE) {
      return Context.of(List.copyOf(journal.entries(agentId.value(), 0)));
    }
    return Context.of(newestWithinBudget(readTail(agentId)));
  }

  @Override
  public void remember(AgentId agentId, HistoryMessage message) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(message, "message must not be null");
    journal.append(agentId.value(), message);
  }

  /** A bounded tail of the journal, so a recall costs the same on day one and day four hundred. */
  private List<HistoryMessage> readTail(AgentId agentId) {
    long head = substrate.head(kind, agentId.value());
    long from = Math.max(0, head - MAX_MESSAGES_READ);
    return journal.entries(agentId.value(), from);
  }

  /**
   * Walks backwards from the newest, keeping what fits.
   *
   * <p>Backwards because the recent past is what a conversation needs; the first message to
   * overflow the budget ends the walk, and everything older is forgotten rather than partially
   * kept. A single message larger than the whole budget is still kept — a context of nothing is
   * worse than a context of one thing that is too big, and the provider will say so.
   */
  private List<ContextMessage> newestWithinBudget(List<HistoryMessage> tail) {
    List<ContextMessage> kept = new ArrayList<>();
    int spent = 0;
    for (int i = tail.size() - 1; i >= 0; i--) {
      HistoryMessage message = tail.get(i);
      int size = charactersOf(message);
      if (!kept.isEmpty() && spent + size > maxCharacters) {
        break;
      }
      kept.add(message);
      spent += size;
    }
    Collections.reverse(kept);
    return kept;
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
