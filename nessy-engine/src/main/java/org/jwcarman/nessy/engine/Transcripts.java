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
package org.jwcarman.nessy.engine;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AssistantMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.spi.substrate.JournalStore;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * What an agent remembers, as an append-only journal.
 *
 * <p>A journal rather than a document because a transcript only ever grows, and rewriting the whole
 * of it to add a line is how a conversation becomes quadratic.
 *
 * <p><b>The pair is written atomically.</b> An assistant turn that called tools and the message
 * answering it are one fact — a transcript holding the first without the second is not a valid
 * {@link Context}, so it must never exist even for the width of a crash. Two appends go through
 * {@code Substrate.batch}, which fails whole or not at all.
 */
public final class Transcripts implements Memory {

  private final JournalStore<Message> journal;
  private final Substrate substrate;

  public Transcripts(Substrate substrate, AgentType agentType) {
    this.substrate = Objects.requireNonNull(substrate, "substrate must not be null");
    Objects.requireNonNull(agentType, "agentType must not be null");
    this.journal =
        substrate.journal(
            "transcript/" + agentType.name(), JsonCodec.of(EngineMapper.INSTANCE, Message.class));
  }

  @Override
  public Context recall(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    return Context.of(journal.entries(agentId.value(), 0));
  }

  @Override
  public void remember(AgentId agentId, UserMessage message) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(message, "message must not be null");
    journal.append(agentId.value(), message);
  }

  @Override
  public void remember(AgentId agentId, AssistantMessage message) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(message, "message must not be null");
    if (!toolCallIdsOf(message).isEmpty()) {
      throw new IllegalArgumentException(
          "an assistant turn that called tools must be remembered with the message answering it");
    }
    journal.append(agentId.value(), message);
  }

  @Override
  public void remember(AgentId agentId, AssistantMessage message, ToolResultMessage results) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(results, "results must not be null");
    requireAnswered(message, results);
    // Journal sequences are 1-based, so the next free slot is one past the count. The agent actor
    // is the only writer for its own id, so reading the count and appending is not a race.
    long next = journal.entries(agentId.value(), 0).size() + 1;
    substrate.batch(
        List.of(
            journal.appendOp(agentId.value(), next, message),
            journal.appendOp(agentId.value(), next + 1, results)));
  }

  private static void requireAnswered(AssistantMessage message, ToolResultMessage results) {
    List<String> asked = toolCallIdsOf(message);
    List<String> answered = results.blocks().stream().map(block -> block.toolUseId()).toList();
    if (!answered.containsAll(asked) || !asked.containsAll(answered)) {
      throw new IllegalArgumentException(
          "results must answer exactly the calls asked: asked " + asked + ", answered " + answered);
    }
  }

  private static List<String> toolCallIdsOf(AssistantMessage message) {
    return message.content().stream()
        .filter(org.jwcarman.nessy.api.block.ToolCallBlock.class::isInstance)
        .map(block -> ((org.jwcarman.nessy.api.block.ToolCallBlock) block).id())
        .toList();
  }
}
