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
package org.jwcarman.nessy.agent.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.Remembrance;

/**
 * {@code recall()}'s provider-legal reassembly (remembrance spec §3), shared by every {@code
 * Memory} this module ships ({@link SubstrateMemory}, {@link VerbatimMemory}): an {@link
 * Remembrance.AssistantMessage} carrying {@code tool_use} blocks is held until every one of those
 * call ids has a matching {@link Remembrance.ToolExchange}, at which point the assistant message
 * and the results message it pairs with both emit, together, in that order — the SAME pairing
 * {@link Context}'s own constructor enforces on the way in.
 *
 * <p>Arrival order between an assistant message and its own exchanges is NOT guaranteed (a tool
 * completing earlier than its siblings folds — and is remembered — the moment it finishes; the
 * assistant message itself is remembered only once every sibling has, alongside the LAST one to
 * finish): an exchange that arrives with no pending assistant message to answer is held in {@link
 * #orphanExchanges} until one claims it. Only one assistant turn is ever outstanding at a time —
 * the reducer never advances past {@code AwaitingTools} until every pending call is answered (Phase
 * spec §2.2) — so nothing here needs to track more than one turn's worth of orphans.
 */
final class RemembranceFold {

  private final List<Message> out = new ArrayList<>();
  private final Map<String, ToolResultBlock> orphanExchanges = new LinkedHashMap<>();
  private Message pendingAssistant;
  private LinkedHashSet<String> pendingIds;
  private Map<String, ToolResultBlock> collected;

  void add(Remembrance remembrance) {
    switch (remembrance) {
      case Remembrance.UserMessage(_, var message) -> out.add(message);
      case Remembrance.AssistantMessage(_, var message) -> beginAssistant(message);
      case Remembrance.ToolExchange(_, var call, var result) ->
          addExchange(call, result.content(), result.isError());
    }
  }

  /** A pre-reform, already-paired legacy entry (spec §6): emitted verbatim, no reassembly. */
  void addLegacy(Message message) {
    out.add(message);
  }

  Context toContext() {
    return Context.of(List.copyOf(out));
  }

  private void beginAssistant(Message message) {
    List<String> ids = toolUseIdsOf(message);
    if (ids.isEmpty()) {
      out.add(message);
      return;
    }
    pendingAssistant = message;
    pendingIds = new LinkedHashSet<>(ids);
    collected = new LinkedHashMap<>();
    for (String id : pendingIds) {
      ToolResultBlock orphan = orphanExchanges.remove(id);
      if (orphan != null) {
        collected.put(id, orphan);
      }
    }
    tryCompletePending();
  }

  private void addExchange(ToolCall call, String content, boolean isError) {
    ToolResultBlock block = new ToolResultBlock(call.id(), content, isError);
    if (pendingIds != null && pendingIds.contains(call.id())) {
      collected.put(call.id(), block);
      tryCompletePending();
    } else {
      orphanExchanges.put(call.id(), block); // its assistant message has not arrived yet
    }
  }

  private void tryCompletePending() {
    if (pendingAssistant == null || !collected.keySet().containsAll(pendingIds)) {
      return;
    }
    out.add(pendingAssistant);
    List<ContentBlock> blocks =
        pendingIds.stream().map(collected::get).map(ContentBlock.class::cast).toList();
    out.add(Message.toolResults(blocks));
    pendingAssistant = null;
    pendingIds = null;
    collected = null;
  }

  private static List<String> toolUseIdsOf(Message message) {
    List<String> ids = new ArrayList<>();
    for (ContentBlock block : message.content()) {
      if (block instanceof ToolUseBlock(ToolCall call, _)) {
        ids.add(call.id());
      }
    }
    return ids;
  }
}
